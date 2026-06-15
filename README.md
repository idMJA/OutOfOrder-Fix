# OutOfOrderFix
A lightweight and efficient Paper/Purpur Minecraft server plugin designed to fix connection timeouts and kicks caused by out-of-order keepalive packets. This is especially useful for Bedrock Editions connecting via **Geyser** proxies.

## The Problem
When Bedrock or mobile players log into a Java server, they often experience a brief latency spike or thread stuttering while loading the terrain.
During this period, the Paper/Purpur server sends network keepalive packets to verify the client's connection. If a player's device responds to an older keepalive ID after the server has already sent a new one, the server's strict packet sequence verification triggers an instant kick with the log message:
```text
Disconnecting <Player> for sending keepalive response (<ID>) out-of-order!
```
This causes players to be disconnected and experience a "Connection Timed Out" error before they even finish loading into the world.

## The Solution
**OutOfOrderFix** intercepts incoming keepalive packets directly in the Netty channel pipeline for each player before the server processes them.
* It checks if the server is currently waiting for a keepalive response (`keepAlivePending`).
* If the incoming packet contains a mismatching or older keepalive ID, it dynamically rewrites the packet ID using Reflection to match the expected ID (`keepAliveId`).
* This satisfies the server's sequence check, preventing the instant kick and allowing players with high latency or loading stutters to successfully join and stay connected.

## Features
* **Zero Configuration:** Plug-and-play with no setup required.
* **Lightweight:** Runs directly inside the Netty pipeline using reflection, incurring negligible CPU and memory overhead.
* **Dependency-Free:** Does not require ProtocolLib or any other third-party packet libraries.

## Requirements
* **Java Version:** Java 25 and up (Java 25+)
* **Minecraft / Server:** Paper / Purpur 26.1.2 and up (26.1.2+)

## Installation
1. Download the latest `OutOfOrderFix.jar`.
2. Place the JAR file inside your server's `plugins/` directory.
3. Start or restart your server (or run `/reload confirm`).
4. Upon successful load, you will see the following message in the console:
   ```text
   [OutOfOrderFix] OutOfOrderFix has been enabled successfully!
   ```
## Logs
When the plugin intercepts and corrects an out-of-order keepalive response, it prints a message to the server console:
```text
[OutOfOrderFix] Fixed out-of-order keepalive response for <PlayerName> (457920559 -> 457920560)
```
