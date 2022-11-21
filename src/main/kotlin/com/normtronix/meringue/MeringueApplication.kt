package com.normtronix.meringue

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class MeringueApplication

fun main(args: Array<String>) {
	runApplication<MeringueApplication>(*args)
}
