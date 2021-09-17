package net.corda.r3.exporter

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
private class Starter

fun main(args: Array<String>) {
  SpringApplication.run(Starter::class.java)
}