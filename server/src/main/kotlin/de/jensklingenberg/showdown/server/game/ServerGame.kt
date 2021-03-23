package de.jensklingenberg.showdown.server.game


import com.soywiz.klock.DateTime
import com.squareup.moshi.Moshi
import de.jensklingenberg.showdown.model.Answer
import de.jensklingenberg.showdown.model.ClientGameConfig
import de.jensklingenberg.showdown.model.GameState
import de.jensklingenberg.showdown.model.Member
import de.jensklingenberg.showdown.model.PATHS
import de.jensklingenberg.showdown.model.QuestionTask
import de.jensklingenberg.showdown.model.Response
import de.jensklingenberg.showdown.model.Result
import de.jensklingenberg.showdown.model.ServerResponse
import de.jensklingenberg.showdown.model.WebSocketResourceType
import de.jensklingenberg.showdown.model.WebsocketResource
import de.jensklingenberg.showdown.model.api.clientrequest.NewGameConfig
import de.jensklingenberg.showdown.model.toJson
import de.jensklingenberg.showdown.server.common.toJson
import de.jensklingenberg.showdown.server.model.Player
import de.jensklingenberg.showdown.server.model.ServerConfig
import de.jensklingenberg.showdown.server.model.Vote
import de.jensklingenberg.showdown.server.model.toClient

enum class Category {
    MULTIPLE
}



/**
 * This class contains the state/logic of a game
 * [server] that will handle a responses to the clients
 * [gameConfig] the config for a game
 */
class ServerGame(private val server: GameServer, var gameConfig: ServerConfig) {

    private val playerList = arrayListOf<Player>()
    private var gameState: GameState = GameState.NotStarted
    private val inactivePlayerIds = mutableSetOf<String>()
    private val spectatorIds = mutableSetOf<String>()
    private val moshi = Moshi.Builder().build()
    private var activePlayerId: String = ""

    val pointsMap: MutableMap<String, Int> = mutableMapOf()

    var taskIndex = 0
    val tasks: List<QuestionTask> =
        listOf(
            QuestionTask(
                "Wie gehts dir?",
                answers = listOf(Answer("Gut", true), Answer("Schlecht", false), Answer("Geht so", false))
            ),
            QuestionTask(
                "Wie spät ist es?",
                answers = listOf(Answer("15Uhr", false), Answer("16Uhr", false), Answer("17uhr", true))
            )
        )

    var activeTask: QuestionTask? = null

    fun changeRoomPassword(sessionId: String, password: String) {
        val newRoomData = gameConfig.room.copy(password = password)
        gameConfig = gameConfig.copy(room = newRoomData)

        val keyword = if (password.isEmpty()) {
            "removed"
        } else {
            "set"
        }

        val response = Response(
            PATHS.MESSAGE.path,
            "Room password was $keyword by: " + playerList.find { it.sessionId == sessionId }?.name
        )


        val websocketResource = WebsocketResource(WebSocketResourceType.RESPONSE, response)
        sendBroadcast(websocketResource.toJson())
        sendRoomConfigUpdate(gameConfig.toClient())
    }

    fun setAutoReveal(autoReveal: Boolean) {
        gameConfig = gameConfig.copy(autoReveal = autoReveal)
        sendRoomConfigUpdate(gameConfig.toClient())
    }

    private fun sendRoomConfigUpdate(clientGameConfig: ClientGameConfig) {
        val configJson = moshi.toJson(clientGameConfig)
        val response = Response(PATHS.ROOMCONFIGUPDATE.path, configJson)
        val websocketResource = WebsocketResource(WebSocketResourceType.RESPONSE, response)
        sendBroadcast(websocketResource.toJson())
    }

    fun restart() {
        println("restart")
        gameState =
            GameState.Started(gameConfig.toClient().copy(createdAt = DateTime.now().unixMillisDouble.toString()))
        inactivePlayerIds.forEach { inactivePlayerId ->
            playerList.removeIf { it.sessionId == inactivePlayerId }
            spectatorIds.removeIf { it == inactivePlayerId }
        }

        inactivePlayerIds.clear()
        clearVotes()
        sendPlayerList()
        sendGameStateChanged(gameState)
    }

    fun chooseNewTask() {
        taskIndex += 1
        activeTask = tasks[taskIndex]
    }

    private fun clearVotes() {
        playerList.replaceAll { it.copy(vote = null) }
    }

    fun playerJoined(player: Player) {

        if (playerList.any { it.sessionId == player.sessionId }) {
            onPlayerRejoined(player.sessionId, player.name)
        } else {
            if (gameState == GameState.NotStarted) {
                gameState = GameState.Started(gameConfig.toClient())
            }
            playerList.add(player)
            server.onPlayerAdded(player.sessionId, player)
            pointsMap.putIfAbsent(player.sessionId, 0)
            sendPlayerList()
            sendGameStateChanged(player.sessionId, gameState)
        }
        sendTask()
    }

    fun sendTask() {
        sendGameStateChanged(GameState.TaskUpdate(tasks[taskIndex]))

    }

    private fun onPlayerRejoined(sessionId: String, name: String) {
        //Set new player name

        playerList.replaceAll { player ->
            if (player.sessionId == sessionId) {
                player.copy(name = name)
            } else {
                player
            }
        }
        inactivePlayerIds.removeIf { it == sessionId }
        sendPlayerList()
        sendGameStateChanged(sessionId, gameState)
    }

    fun newRound() {
        activePlayerId = ""


        chooseNewTask()
        sendTask()
    }

    fun changeConfig(clientGameConfig: NewGameConfig) {
        this.gameConfig = gameConfig.copy(
            createdAt = DateTime.now().unixMillisLong.toString(),
            autoReveal = clientGameConfig.autoReveal,
            voteOptions = clientGameConfig.voteOptions
        )
        clearVotes()
        gameState = GameState.Started(this.gameConfig.toClient())
        sendGameStateChanged(gameState)
        sendPlayerList()
    }


    fun onSpectate(sessionId: String, spectate: Boolean) {

        if (spectate) {
            spectatorIds.add(sessionId)
            playerList.replaceAll { player ->
                if (player.sessionId == sessionId) {
                    player.copy(vote = null)
                } else {
                    player
                }
            }
        } else {
            spectatorIds.remove(sessionId)
        }
        val response = Response(PATHS.SPECTATORPATH.path, moshi.toJson(spectate))
        val websocketResource = WebsocketResource(WebSocketResourceType.RESPONSE, response)

        server.sendData(sessionId, websocketResource.toJson())
        sendPlayerList()
    }

    fun onPlayerVoted(sessionId: String, voteId: Int) {
        if (gameState is GameState.ShowVotes) {
            return
        }
        if (spectatorIds.any { it == sessionId }) {
            return
        }
        playerList.replaceAll { player ->
            if (player.sessionId == sessionId) {
                player.copy(vote = Vote(voteId, sessionId))
            } else {
                player
            }
        }

        sendPlayerList()
        if (gameConfig.autoReveal) {

            if (playerList
                    .filter { !inactivePlayerIds.contains(it.sessionId) }
                    .filter { !spectatorIds.contains(it.sessionId) }
                    .all { it.vote != null }
            ) {
                sendVotes()
            }
        }
    }

    fun onPlayerLostConnection(sessionId: String) {
        playerList.find { it.sessionId == sessionId }?.let {
            inactivePlayerIds.add(it.sessionId)
        }

        sendPlayerList()
        closeRoomIfEmpty()
    }

    //When all players are inactive, delete the room
    private fun closeRoomIfEmpty() {
        if (playerList.size == inactivePlayerIds.size) {
            playerList.forEach {
                server.removePlayer(it.sessionId)
            }
            server.closeRoom(gameConfig.room.name)
        }
    }

    private fun sendPlayerList() {
        val votesList = playerList.map { player ->
            val isActive = inactivePlayerIds.none { it == player.sessionId }
            val isSpectator = spectatorIds.any { it == player.sessionId }
            val voted = player.vote != null
            Member(player.name, voted, isConnected = isActive, isSpectator = isSpectator)
        }.sortedBy { it.voted }
        sendGameStateChanged(GameState.PlayerListUpdate(votesList))
    }

    fun showVotes(sessionId: String) {
        sendVotes()
    }

    fun onBuzzerPressed(sessionId: String) {
        println("onBuzzer")
        if (activePlayerId.isEmpty()) {
            activePlayerId = sessionId
        }
    }

    fun onAnswered(sessionId: String, answer: String) {
        if (activePlayerId != sessionId) {
            return
        }

        pointsMap[sessionId] = (pointsMap[sessionId] ?: 0) + if (checkAnswer(sessionId, answer)) {
            1000
        } else {
            -1000
        }
        sendCharts()
        newRound()
        println("HALLO" + answer)
    }

    fun checkAnswer(sessionId: String, answer: String): Boolean {
        // val answers = tasks[0].correctAnswer + tasks[0].incorrectAnswers
        val index = answer.toInt()
        return true
    }

    private fun sendCharts() {
        val votesList = playerList.map { player ->
            val isActive = inactivePlayerIds.none { it == player.sessionId }
            val isSpectator = spectatorIds.any { it == player.sessionId }
            val voted = player.vote != null
            Member(
                player.name,
                voted,
                isConnected = isActive,
                isSpectator = isSpectator,
                points = pointsMap.getOrDefault(player.sessionId, 0)
            )
        }.sortedBy { it.voted }
        sendGameStateChanged(GameState.PlayerListUpdate(votesList))
    }

    private fun sendVotes() {
        if (playerList.none { it.vote != null }) {
            return
        }
        val newVotes = playerList.filter { it.vote != null }.map {
            val voteText = gameConfig.voteOptions[it.vote!!.voteId]
            Result(voteText, it.name)
        }.sortedBy { it.optionName }

        gameState = GameState.ShowVotes(newVotes)
        sendGameStateChanged(gameState)
    }


    private fun sendGameStateChanged(sessionId: String, gameState: GameState) {
        val json = ServerResponse.GameStateChanged(gameState).toJson()
        server.sendData(sessionId, json)
    }

    private fun sendGameStateChanged(gameState: GameState) {
        println("sendGameStateChanged " + ServerResponse.GameStateChanged(gameState).toJson())
        sendBroadcast(ServerResponse.GameStateChanged(gameState).toJson())
    }

    private fun sendBroadcast(json: String) {
        playerList.forEach {
            server.sendData(it.sessionId, json)
        }
    }

}

