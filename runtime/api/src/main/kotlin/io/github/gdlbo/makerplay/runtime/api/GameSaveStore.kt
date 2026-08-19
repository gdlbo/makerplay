package io.github.gdlbo.makerplay.runtime.api

interface GameSaveStore {
    fun read(gameId: String, key: String): ByteArray?
    fun write(gameId: String, key: String, payload: ByteArray)
    fun delete(gameId: String, key: String): Boolean
    fun keys(gameId: String): Set<String>
}

interface InitialGameSaveStore : GameSaveStore {
    fun importInitial(gameId: String, entries: Map<String, ByteArray>)
}

class GameSaveLimitException(message: String) : IllegalStateException(message)

class GameSaveCorruptionException(gameId: String, key: String) :
    IllegalStateException("Save data is corrupt for game '$gameId' and key '$key'")

class GameSaveStorageException(gameId: String, key: String, operation: String) :
    IllegalStateException("Unable to $operation save data for game '$gameId' and key '$key'")