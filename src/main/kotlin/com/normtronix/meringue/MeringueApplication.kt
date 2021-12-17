package com.normtronix.meringue

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MeringueApplication

fun main(args: Array<String>) {
	runApplication<MeringueApplication>(*args)
}
