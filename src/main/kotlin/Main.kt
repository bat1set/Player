package main

fun main(args: Array<String>) {
    val options = parsePlayerOptions(args) ?: return
    val player = VideoPlayer(options)
    player.run()
}
