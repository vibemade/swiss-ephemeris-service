# swiss-ephemeris-service

A stateless gRPC service for astronomical positions, built on the [Swiss Ephemeris](https://www.astro.com/swisseph/)
by Astrodienst AG. Every request carries all of its inputs, so responses can be cached by request and the
service can run as any number of identical replicas.

The contract is [`ephemeris.proto`](src/main/proto/me/lagna/ephemeris/v1/ephemeris.proto)
(package `me.lagna.ephemeris.v1`); client developers start with the
[integration guide](docs/INTEGRATION.md).

| RPC | Since | What it returns |
|---|---|---|
| `GetPositions` | 0.1 | Positions and speeds of several bodies at several instants, tropical or sidereal |
|  | 0.2 | … optionally with right ascension, declination, true obliquity and nutation (`equatorial`) |
| `StreamPositions` | 0.1 | Positions sampled at a fixed step over a range, streamed in chunks |
| `GetAyanamsa` | 0.1 | Ayanamsa values for several instants |
| `GetHouses` | 0.1 | Ascendant, MC, ARMC, Vertex and house cusps (8 house systems) |
|  | 0.2 | … with bhava madhyas and sandhis for Sripati houses |
| `GetRiseSet` | 0.1 | Rise, set and meridian transits of a body |
| `FindCrossings` | 0.1 | Exact instants a body crosses a grid or list of longitudes (signs, nakshatras, …) |
| `FindStations` | 0.1 | Exact instants a body turns retrograde or direct |
| `FindPairCrossings` | 0.2 | Exact instants the difference or sum of two longitudes crosses a grid or list (tithis, karanas, nithya yogas, conjunctions) |
| `FindAngleCrossings` | 0.2 | Exact instants the ascendant or MC of a place crosses a grid or list (lagna signs, navamsas) |
| `FindEclipses` | 0.2 | Solar or lunar eclipses in a range, global or as seen from a place |
| `FindReturns` | 0.2 | Returns of a body or a pair to its natal value (solar and lunar returns, tithi pravesha) |
| `GetSolarTime` | 0.2 | Local mean and local apparent time offsets (equation of time) |
| `GetUpagrahas` | 0.2 | Dhuma and Kala groups of upagrahas, with the eight portions of the day or night |
| `GetMeanPositions` | 0.2 | Mean longitudes and sighrocca of the Sun, Moon and Mercury to Saturn |
| `GetInfo` | 0.1 | Engine version, supported date range, limits and the source code location |

| Input | Supported |
|---|---|
| Bodies | Sun to Pluto, mean and true node, mean apogee, Chiron; since 0.2 the south nodes (Ketu) `MEAN_SOUTH_NODE` and `TRUE_SOUTH_NODE` |
| Zodiac | Tropical, or sidereal with one of 21 ayanamsas: Lahiri, Raman, Krishnamurti, Fagan-Bradley, Yukteshwar, True Chitrapaksha, True Revati, True Pushya; since 0.2 Usha-Shashi, Surya Siddhanta (and mean Sun), Aryabhata (and mean Sun), SS Revati, SS Citra, True Mula, Aryabhata 522, Lahiri 1940, Lahiri VP285, Krishnamurti VP291, Lahiri ICRC |
| House systems | Whole sign, equal, Placidus, Sripati, Koch, Porphyry, Regiomontanus, Campanus |
| Instants | 1800-01-02 to 2400-01-01 UTC, the range of the bundled `.se1` files |

The service refuses to start if the `.se1` files are missing instead of silently falling back to the less
precise Moshier model.

## Build and run

Needs JDK 25, a C compiler and `curl`. The build fetches the pinned Swiss Ephemeris sources and the
`.se1` files, verifies each against [`native/swisseph.sha256`](native/swisseph.sha256) and compiles
`libswe`, which the service calls through the Java Foreign Function & Memory API.

```bash
./gradlew build       # libswe, code generation, tests
./gradlew perfTest    # timing of crossing searches with and without speed (build/perf/results.txt)
./gradlew run         # gRPC on :50051
grpcurl -plaintext -d '{"instants":["2000-01-01T12:00:00Z"],"bodies":["SUN","MOON"],
  "zodiac":{"sidereal":{"ayanamsa":"LAHIRI"}}}' localhost:50051 me.lagna.ephemeris.v1.Ephemeris/GetPositions
```

Server reflection and the standard gRPC health service are enabled.

## Releases

Each [GitHub release](https://github.com/vibemade/swiss-ephemeris-service/releases) carries
`swiss-ephemeris-service-linux-amd64.tar.gz`, `swiss-ephemeris-service-linux-arm64.tar.gz` and `SHA256SUMS`.
An archive holds the application, libswe for its platform and the `.se1` files. It needs a JRE 25 on a
glibc-based Linux with glibc 2.35 or newer (Ubuntu 22.04+, Debian 12+, RHEL 10+); musl distributions such as
Alpine are not supported:

```bash
tar -xzf swiss-ephemeris-service-linux-amd64.tar.gz
swiss-ephemeris-service/bin/swiss-ephemeris-service              # gRPC on :50051
swiss-ephemeris-service/bin/swiss-ephemeris-service healthcheck  # exit 0 when serving
```

`GetInfo` reports the running release in `service_version`.

Maintainers release by pushing a tag (`git tag v0.1.0 && git push origin v0.1.0`) or by running the **Release**
workflow with a tag. The workflow builds, tests and smoke-tests both archives and publishes the GitHub release
only after all of that passes, so `releases/latest` never lacks its files; a tag with a suffix such as `-rc.1`
becomes a pre-release.

## Run with Docker

[`Dockerfile`](Dockerfile) downloads the latest release archive for the build platform, checks it against
`SHA256SUMS` and runs it as a non-root user on a JRE with a gRPC health check. [`compose.yaml`](compose.yaml)
re-checks the latest release on every `up` and rebuilds only when it changed:

```bash
docker compose up -d                     # the latest release; healthy once the self-check has passed
RELEASE=download/v0.1.0 docker compose up -d --build   # a pinned release
docker compose run --rm grpcurl          # GetInfo from a client container on the same network
docker compose down
```

To build from this checkout's sources instead: `docker compose -f compose.yaml -f compose.source.yaml up -d --build`
([`Dockerfile.source`](Dockerfile.source)). Configure the container with the environment variables below;
TLS, public addresses and scaling are left to your deployment.

## Configuration

Environment variables (system properties in brackets, used by Gradle runs and tests):

| Variable           | Default                       | Meaning                                                |
|--------------------|-------------------------------|--------------------------------------------------------|
| `PORT`             | `50051`                       | gRPC port                                               |
| `SWE_LIB`          | archive's `native/libswe.so` (`swe.lib`) | Path to `libswe.so` / `libswe.dylib`         |
| `SWE_EPHE`         | archive's `ephe/` (`swe.ephe`) | Directory with the `.se1` files                        |
| `SWE_THREADS`      | CPU count (1 on macOS)        | libswe worker threads                                   |
| `SWE_QUEUE`        | `256`                         | Requests waiting for a worker; more get `RESOURCE_EXHAUSTED` |
| `MAX_ITEMS`        | `10000`                       | Limit per request: instants × bodies, queries, results  |
| `MAX_STREAM_ITEMS` | `1000000`                     | Limit per stream: samples × bodies                      |
| `CACHE_MAX_MB`     | `64`                          | In-process cache of search results; `0` disables it     |
| `SEARCH_WITHOUT_SPEED` | `true`                    | Crossing searches compute longitudes without `SEFLG_SPEED` |
| `SOURCE_URL`       | this repository               | Reported by `GetInfo`                                   |

## Design notes

- **Threads.** libswe keeps its settings in thread-local storage, so all calls run on a fixed pool of
  platform threads, and each call sets the modes it needs (sidereal mode, topocentric observer). A whole
  request, or a whole search, runs in one session on one worker.
- **Searches.** Stations are sign changes of the longitude speed, sampled every 4 days for Mercury, 0.1 day
  for the true node and 10 days for other bodies (at least 4 times shorter than the shortest gap between
  stations over 1800–2400) and refined by bisection. Between stations the longitude is monotonic, so crossings
  are found from longitudes sampled once a day plus the stations, each refined by bisection to about 0.1 s.
  Over 1900–2100 these steps found the same stations and missed none of the sign, nakshatra or pada
  crossings found by sampling every 0.005 day. Limits: true node stations closer than 0.1 day are not
  resolved (its crossing searches split at a 0.5-day step, which skips only loops under 0.014°), and the
  rare Neptune and Pluto station clusters within an hour (under 0.5″) are reported as one station.
- **Pair searches.** The difference or sum of two longitudes is searched like one body. Its stations are where the
  two speeds cancel; with the Moon there are none (it always moves at least 11.7° a day, no other body over 2.3),
  otherwise the speeds are sampled every 0.5 day for pairs with Mercury, Venus, Mars or the true node and every
  2 days for slower pairs, and the combined longitude every day (with the Moon) or 10 days. Unlike a body's own
  stations, a pair's can come arbitrarily close where two speeds barely touch. Over 2000–2040 the 0.5-day step
  found every station a 0.02-day step found for Mercury ± Venus, Sun ± Mercury, Venus ± Mars and Mercury ±
  Jupiter; over 1900–2100 the 2-day step missed only a few station pairs of slow planets, skipping loops of 2″
  (Jupiter − Saturn) and 4″ (Saturn − Uranus).
- **Angle searches.** Within the polar circles the ascendant only moves forward, as the midheaven does everywhere,
  covering a full turn per sidereal day, however unevenly. Both are sampled every 6 hours and each target is
  bracketed by the distance travelled rather than by angles, so a sweep of more than 180° between samples (near
  the polar circles) stays unambiguous. Beyond ±66.5° the ascendant jumps and moves backwards, and
  `FindAngleCrossings` refuses it.
- **Eclipses.** `swe_sol_eclipse_when_glob` / `_when_loc` and `swe_lun_eclipse_when` / `_when_loc`, called from
  one maximum to the next. Over 2026 the maxima, magnitudes and lunar contacts agree with NASA's catalogue to
  about a minute (NASA's ΔT differs by a few seconds).
- **Searches without speed.** Crossing searches only need longitudes, so by default they call libswe without
  `SEFLG_SPEED` (about 5 µs instead of 9 µs per call); station searches still use the speed. Over 2000–2050,
  all 14 bodies, tropical and Lahiri, sign and nakshatra grids, the 69,397 crossings found both ways were
  identical to the millisecond, and calls with speed dropped from 4.7 to 1.6 million. Set
  `SEARCH_WITHOUT_SPEED=false` to always request speed.
- **Cache.** `ResultCache` is a small interface; `CaffeineResultCache` keeps the results of the searches
  (`FindCrossings`, `FindStations`, `FindPairCrossings`, `FindAngleCrossings`, `FindEclipses`, `FindReturns`)
  in memory. A shared cache can implement the same interface using `CacheKey.digest()` (SHA-256 of the method
  and the deterministically serialised request).
- **Jyotish points.** The service computes a few points that follow from astronomy and simple arithmetic alone:
  Ketu, Sripati bhavas, upagrahas (BPHS 3.61–70) and mean planets (Simon et al. 1994; the Moon per Meeus 47.1).
  Rules of interpretation stay with the clients.
- **Time.** Instants are UTC and are used as UT1; the difference is under a second.

## License

GNU Affero General Public License v3.0 or later, see [LICENSE](LICENSE). The Swiss Ephemeris is
© Astrodienst AG and is used under its AGPL licence option. If you run a modified version of this service
for others, section 13 of the AGPL requires you to offer them its source code.
