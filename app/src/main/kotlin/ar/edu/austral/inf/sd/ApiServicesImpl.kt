package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.*
import ar.edu.austral.inf.sd.server.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.*
import java.util.concurrent.TimeUnit

@Component
class ApiServicesImpl @Autowired constructor(
    private val restTemplate: RestTemplate
): RegisterNodeApiService, RelayApiService, PlayApiService, UnregisterNodeApiService,
    ReconfigureApiService {
    @Value("\${server.name:nada}")
    private val myServerName: String = ""

    @Value("\${server.port:8080}")
    private val myServerPort: Int = 0

    private var myRegisterHost = ""
    private var myRegisterPort = 0
    private var serverTimeout: Int = 10

    private val nodes: MutableList<RegisterResponse> = mutableListOf()
    private var nextNode: RegisterResponse? = null
    private val messageDigest = MessageDigest.getInstance("SHA-512")
    private val salt = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    private val currentRequest
        get() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    private var resultReady = CountDownLatch(1)
    private var currentMessageWaiting = MutableStateFlow<PlayResponse?>(null)
    private var currentMessageResponse = MutableStateFlow<PlayResponse?>(null)
    private var xGameTimestamp: Int = 0
    private var timeouts: Int = 0
    private var ownSalt: String = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    private var ownUuid = UUID.randomUUID()
    private val registeredPlayers: MutableList<Player> = mutableListOf()

    private var lastTimestamp = 0

    override fun registerNode(host: String?, port: Int?, uuid: UUID?, salt: String?, name: String?): ResponseEntity<RegisterResponse> {

        registeredPlayers.forEach { player ->
            if (player.uuid == uuid && player.salt == salt) {
                nodes.forEach() { node ->
                    if (node.nextHost == host) {
                        return ResponseEntity(RegisterResponse(node.nextHost, node.nextPort, node.timeout, node.xGameTimestamp), HttpStatus.ACCEPTED)
                    }
                }
            }
//            else if (player.uuid == uuid && player.salt != salt) {
//                throw UnauthorizedException("Salt does not match")
//            }
        }

        val nextNode = if (nodes.isEmpty()) {
            // es el primer nodo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, timeouts, xGameTimestamp)
            nodes.add(me)
            registeredPlayers.add(Player(name!!, uuid!!, host!!, port!!, salt!!))
            me
        } else {
            nodes.last()
        }
        val node = RegisterResponse(host!!, port!!, timeouts, xGameTimestamp)
        nodes.add(node)
        registeredPlayers.add(Player(name!!, uuid!!, host, port, salt!!))

        return ResponseEntity(RegisterResponse(nextNode.nextHost, nextNode.nextPort, timeouts, xGameTimestamp), HttpStatus.OK)
    }

    override fun relayMessage(message: String, signatures: Signatures, xGameTimestamp: Int?): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        val receivedContentType = currentRequest.getPart("message")?.contentType ?: "nada"
        val receivedLength = message.length
        if (nextNode != null) {
            val updatedSignatures = signatures.items + clientSign(message, receivedContentType)
            sendRelayMessage(message, receivedContentType, nextNode!!, Signatures(updatedSignatures), xGameTimestamp)
        } else {
            // me llego algo, no lo tengo que pasar
//            if (currentMessageWaiting.value == null) throw BadRequestException("no waiting message")
            val current = currentMessageWaiting.getAndUpdate { null }!!
            val response = current.copy(
                contentResult = if (receivedHash == current.originalHash) "Success" else "Failure",
                receivedHash = receivedHash,
                receivedLength = receivedLength,
                receivedContentType = receivedContentType,
                signatures = signatures
            )
            currentMessageResponse.update { response }
            resultReady.countDown()
        }
        return Signature(
            name = myServerName,
            hash = receivedHash,
            contentType = receivedContentType,
            contentLength = receivedLength
        )
    }

    override fun sendMessage(body: String): PlayResponse {

        //The coordinator does not accept more than 10 failures (cases 503 or 504)
//        if (timeouts >= 10) throw BadRequestException("We are not playing anymore")

        //TODO NO SE SI ES NECESARIO
//        if (nodes.isEmpty()) {
//            // inicializamos el primer nodo como yo mismo
//            val me = RegisterResponse(myServerPort, myServerName, myUUID, mySalt)
//
//            nodes.add(me)
//        }
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType

        val expectedSignatures = expectedSignatures(body, registeredPlayers, contentType)
        val lastNode = nodes.last()

        sendRelayMessage(body, contentType, RegisterResponse(lastNode.nextHost, lastNode.nextPort, serverTimeout, -1 ), Signatures(listOf()), xGameTimestamp)
        resultReady.await(timeouts.toLong(), TimeUnit.SECONDS)
        resultReady = CountDownLatch(1)

        if (currentMessageResponse.value == null){
            timeouts += 1
            throw TimeOutException("Last relay was not received on time")
        }

        if (doHash(body.encodeToByteArray(),  ownSalt) != currentMessageResponse.value!!.receivedHash){
            timeouts += 1
            throw NoServiceAvailableException("Received different hash than original")
        }

        if (!compareSignatures(expectedSignatures, currentMessageResponse.value!!.signatures)){
            throw InternalErrorException("Missing signatures")
        }

        return currentMessageResponse.value!!
    }

    override fun unregisterNode(uuid: UUID?, salt: String?): String {
        val nodeToUnregister = registeredPlayers.find {
            it.uuid == uuid!!
        }

        if (nodeToUnregister == null){
            throw NotFoundException("Node with uuid: $uuid not found")
        }

        if (nodeToUnregister.salt != salt){
            throw BadRequestException("Invalid data")
        }

        val nodeToUnregisterIndex = registeredPlayers.indexOf(nodeToUnregister)

        if (nodeToUnregisterIndex < registeredPlayers.size - 1){
            val previousNode = registeredPlayers[nodeToUnregisterIndex+1]
            val nextNode = registeredPlayers[nodeToUnregisterIndex-1]

            val reconfigureUrl = "http://${previousNode.host}:${previousNode.port}/reconfigure"
            val reconfigureParams = "?uuid=${previousNode.uuid}&salt=${previousNode.salt}&nextHost=${nextNode.host}&nextPort=${nextNode.port}"

            val url = reconfigureUrl + reconfigureParams

            val requestHeaders = HttpHeaders().apply {
                add("X-Game-Timestamp", xGameTimestamp.toString())
            }
            val request = HttpEntity(null, requestHeaders)

            try {
                restTemplate.postForEntity<String>(url, request)
            } catch (e: RestClientException){
                print("Could not reconfigure to: $url")
                throw e
            }
        }

        nodes.removeAt(nodeToUnregisterIndex)
        return "Unregister Successful"
    }

    override fun reconfigure(
        uuid: UUID?,
        salt: String?,
        nextHost: String?,
        nextPort: Int?,
        xGameTimestamp: Int?
    ): String {
        if (uuid != ownUuid || salt != ownSalt) {
            throw BadRequestException("The data provided does not match the current node")
        }
        //faltaría ver eso del timestamp
        return "Change accepted"
    }

    internal fun registerToServer(registerHost: String, registerPort: Int) {
        val registerUrl = "http://$registerHost:$registerPort/register-node?host=localhost&port=$myServerPort&name=$myServerName&uuid=$ownUuid&salt=$ownSalt"
        try {
            val response = restTemplate.postForEntity<RegisterResponse>(registerUrl)
            myRegisterHost = registerHost
            myRegisterPort = registerPort
            val registerNodeResponse: RegisterResponse = response.body!!
            println("nextNode = $registerNodeResponse")
            nextNode = with(registerNodeResponse) { RegisterResponse(nextHost, nextPort, timeout, registerNodeResponse.xGameTimestamp) }
        } catch (e: RestClientException){
            println("Could not register to: $registerUrl")
            println("Error: ${e.message}")
            println("Shutting down")
        }
    }

    private fun sendRelayMessage(
        body: String,
        contentType: String,
        relayNode: RegisterResponse,
        signatures: Signatures,
        xGameTimestamp: Int?
    ) {
//        if (xGameTimestamp!! <= lastTimestamp){
//            throw BadRequestException("The timestamp is not valid")
//        }
        lastTimestamp = xGameTimestamp!!

        val relayUrl = "http://${relayNode.nextHost}:${relayNode.nextPort}/relay"

        val headers = HttpHeaders()
        headers.contentType = MediaType.MULTIPART_FORM_DATA

        val formData = LinkedMultiValueMap<String, Any>()

        val messageHeaders = HttpHeaders()
        messageHeaders.contentType = MediaType.parseMediaType(contentType)
        val messageEntity = HttpEntity(body, messageHeaders)
        formData.add("message", messageEntity)

        val signaturesHeaders = HttpHeaders()
        signaturesHeaders.contentType = MediaType.APPLICATION_JSON
        val signaturesEntity = HttpEntity(signatures, signaturesHeaders)
        formData.add("signatures", signaturesEntity)

        try {
            val response = restTemplate.postForEntity<Map<String, Any>>(relayUrl, HttpEntity(formData, headers))
            println("Response from relay: ${response.statusCode} - ${response.body}")
        }catch (e: RestClientException){
            val hostUrl = "http://${myRegisterHost}:${myRegisterPort}/relay"
            restTemplate.postForEntity<Map<String, Any>>(hostUrl, HttpEntity(formData, headers))
            throw NoServiceAvailableException("Could not send message to relay")
        }

    }

    private fun clientSign(message: String, contentType: String): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), ownSalt)
        return Signature(myServerName, receivedHash, contentType, message.length)
    }

    private fun newResponse(body: String) = PlayResponse(
        "Unknown",
        currentRequest.contentType,
        body.length,
        doHash(body.encodeToByteArray(), salt),
        "Unknown",
        -1,
        "N/A",
        Signatures(listOf())
    )

    private fun doHash(body: ByteArray, salt: String): String {
        val cleanedSalt = cleanBase64(salt)
        val saltBytes = Base64.getDecoder().decode(cleanedSalt)
        messageDigest.update(saltBytes)
        val digest = messageDigest.digest(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun cleanBase64(base64: String): String {
        return base64.replace("\\s".toRegex(), "")
    }
    private fun expectedSignatures(body: String, nodes: MutableList<Player>, contentType: String): Signatures {
        val signatures = mutableListOf<Signature>()
        nodes.forEach { node ->
            signatures.add(Signature(node.name, doHash(body.encodeToByteArray(), node.salt), contentType, body.length))
        }
        return Signatures(signatures)
    }

    private fun compareSignatures(a: Signatures, b: Signatures): Boolean{
        val aSignatureList = a.items
        val bSignatureList = b.items.reversed()

        if (aSignatureList.size != bSignatureList.size) return false

        for (i in aSignatureList.indices) {
            if (aSignatureList[i].hash != bSignatureList[i].hash) return false
        }

        return true
    }

    companion object {
        fun newSalt(): String = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    }
}
