package main

import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Log {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun info(message: String) {
        println("[${LocalTime.now().format(formatter)}] $message")
        System.out.flush()
    }

    fun error(message: String, error: Throwable? = null) {
        println("[${LocalTime.now().format(formatter)}] ERROR: $message")
        error?.printStackTrace(System.out)
        System.out.flush()
    }
}
