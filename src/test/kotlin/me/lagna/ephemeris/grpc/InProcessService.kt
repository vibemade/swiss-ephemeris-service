// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import me.lagna.ephemeris.TestEphemeris
import me.lagna.ephemeris.cache.ResultCache
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.BodyPair
import me.lagna.ephemeris.v1.EphemerisGrpcKt
import me.lagna.ephemeris.v1.PairCombination
import me.lagna.ephemeris.v1.Sidereal
import me.lagna.ephemeris.v1.Zodiac

/** The service behind an in-process channel, for tests that call it as a client would. */
class InProcessService(
    cache: ResultCache,
) : AutoCloseable {
    private val name = InProcessServerBuilder.generateName()
    private val server: Server =
        InProcessServerBuilder
            .forName(name)
            .directExecutor()
            .addService(EphemerisService(TestEphemeris.instance, cache))
            .build()
            .start()
    private val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()
    val client = EphemerisGrpcKt.EphemerisCoroutineStub(channel)

    override fun close() {
        channel.shutdownNow()
        server.shutdownNow()
    }
}

fun pair(
    bodyA: Body,
    bodyB: Body,
    combination: PairCombination,
): BodyPair =
    BodyPair
        .newBuilder()
        .setBodyA(bodyA)
        .setBodyB(bodyB)
        .setCombination(combination)
        .build()

fun sidereal(ayanamsa: Ayanamsa): Zodiac =
    Zodiac
        .newBuilder()
        .setSidereal(Sidereal.newBuilder().setAyanamsa(ayanamsa))
        .build()
