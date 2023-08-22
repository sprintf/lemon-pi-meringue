package com.normtronix.meringue

data class TrackAndCar(
    val trackCode: String,
    val carNumber: String
) {

    companion object {
        fun from(colonSep: String): TrackAndCar {
            val trackAndCar = colonSep.split(":")
            return TrackAndCar(trackAndCar[0], trackAndCar[1])
        }
    }
}
