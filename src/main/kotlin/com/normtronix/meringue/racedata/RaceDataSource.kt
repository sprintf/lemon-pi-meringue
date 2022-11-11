package com.normtronix.meringue.racedata

interface RaceDataSource {

    var logRaceData: Boolean

    fun connect() : Any

    suspend fun stream(context: Any, handler: BaseDataSourceHandler) : Unit
}