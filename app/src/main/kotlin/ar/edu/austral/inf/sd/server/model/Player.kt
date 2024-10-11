package ar.edu.austral.inf.sd.server.model

data class Player(
    val name: kotlin.String,
    val uuid: java.util.UUID,
    val host: kotlin.String,
    val port: kotlin.Int,
    val salt: kotlin.String,
    ) {

}
