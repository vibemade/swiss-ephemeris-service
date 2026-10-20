# Ephemeris gRPC API — Integration Guide

Last updated: 2026-10-20 (release 0.2.2)

## Overview

The service computes astronomical positions with the Swiss Ephemeris 2.10.03 and exposes them over gRPC. Every request carries all of its inputs: there are no accounts, sessions or stored data, so any client in any language can call it and any response can be cached by its request.

- **Contract:** [`ephemeris.proto`](../src/main/proto/me/lagna/ephemeris/v1/ephemeris.proto), package `me.lagna.ephemeris.v1`, service `Ephemeris`, 15 RPCs. The proto file is the source of truth; this guide explains how to use it.
- **Transport:** gRPC over HTTP/2. Server reflection and the standard gRPC health service are enabled.
- **What it computes:** positions and speeds of 16 bodies (with Ketu), equatorial coordinates, ayanamsa, house cusps and angles, rise/set/transit times, exact sign or degree crossings and retrograde stations; exact instants for the difference or sum of two bodies (tithis, karanas, yogas, conjunctions), for the ascendant or midheaven, and for returns to a natal value; eclipses; local mean and apparent time; and a few Jyotish points that follow from astronomy alone: Sripati bhavas, upagrahas and mean planets.
- **What it leaves to you:** converting a local birth time to UTC, turning a place name into coordinates, and any astrological interpretation. The service only takes UTC instants and latitude/longitude.

## Getting started

You need three things: the endpoint, a client generated from the proto, and one test call.

1. **Get the endpoint** of the instance you call, as `<host>:<port>`. The service itself listens on port 50051 with plaintext HTTP/2 (h2c); TLS, proxies and the public address are part of each deployment, not of this API. Drop `-plaintext` from the commands below when your endpoint uses TLS.
2. **Generate a client** from `ephemeris.proto` with `protoc` or `buf`. The file imports only `google/protobuf/timestamp.proto`, which ships with every protobuf toolchain. Pin the proto to a commit or tag of the repository.
3. **Make a test call** with [grpcurl](https://github.com/fullstorydev/grpcurl), which needs no proto file thanks to reflection:

```bash
grpcurl -plaintext <host>:50051 list
grpcurl -plaintext <host>:50051 me.lagna.ephemeris.v1.Ephemeris/GetInfo
grpcurl -plaintext -d '{"service": ""}' <host>:50051 grpc.health.v1.Health/Check
```

`GetInfo` returns the engine version, the supported date range, the per-request limit and the source code URL. The health check returns `SERVING` once the ephemeris files have passed their startup check.

| Stack | Code generation |
| --- | --- |
| Go | `protoc-gen-go` + `protoc-gen-go-grpc` |
| Java / Kotlin | `protoc-gen-grpc-java` (+ `protoc-gen-grpc-kotlin` for coroutine stubs) |
| Python | `grpcio-tools` |
| Node.js / TypeScript | `@grpc/grpc-js` with `ts-proto` or `buf` generated stubs |
| .NET | `Grpc.Tools` |
| Rust | `tonic-build` |
| Browser | Not supported directly: gRPC-Web needs a proxy such as Envoy, which the service does not provide |

## Conventions

Most integration bugs come from time zones and sign conventions, so read this section before writing code.

- **Time** is `google.protobuf.Timestamp` in UTC (in JSON: RFC 3339, `"2000-01-01T12:00:00Z"`). The service treats it as UT1; the two differ by under 0.9 s. Convert local civil time to UTC yourself with a time-zone database that holds historical offsets.
- **Supported range** is 1800-01-02T00:00Z to 2400-01-01T00:00Z, also returned by `GetInfo`. Any instant outside it fails with `OUT_OF_RANGE`.
- **Geographic coordinates** are degrees with **north and east positive**: Kyiv is latitude 50.45, longitude 30.52; New York is longitude −74.0. Altitude is in metres and optional; `GetRiseSet`, `GetUpagrahas` and `FindEclipses` accept −500 to 25,000 m.
- **Angles** are degrees. Ecliptic longitudes are in [0, 360). Speeds are degrees per day and are negative while a body is retrograde. Distances are in astronomical units.
- **Zodiac:** omit `zodiac` (or send `tropical`) for tropical longitudes; send `sidereal` with an `ayanamsa` for sidereal ones. Every longitude in a request and its response uses the same zodiac.
- **Positions** are geocentric and apparent; pass `observer` to `GetPositions` for topocentric ones. Set `equatorial` to also get right ascension and declination (true equator and equinox of date, whatever the zodiac).
- **Durations** (`GetSolarTime`) are `google.protobuf.Duration`, in JSON a decimal number of seconds with an `s` suffix (`"7324.800s"`); east and positive offsets mean local time is ahead of UTC.
- **Batches keep their order:** results come back in the order of the request's instants, bodies or queries; `GetPositions` results also repeat their instant and body.
- **Enums:** every `*_UNSPECIFIED` value (0) is rejected with `INVALID_ARGUMENT`, except `DiscConvention`, whose default is described under `GetRiseSet`. Treat enum values you do not know as possible: the contract may add some.
- **Absent fields mean "none":** a `RiseSetTime` without `instant` means the event does not happen that day (polar day or night); an `Eclipse` without `totality_begin` has no total or annular phase (there); a `MeanPosition` without `sighrocca` is the Sun or the Moon; the eighth `DayPortion` has no `lord`.

| Enum | Values |
| --- | --- |
| `Body` | `SUN`, `MOON`, `MERCURY`, `VENUS`, `MARS`, `JUPITER`, `SATURN`, `URANUS`, `NEPTUNE`, `PLUTO`, `MEAN_NODE`, `TRUE_NODE`, `MEAN_APOGEE`, `CHIRON`, `MEAN_SOUTH_NODE`, `TRUE_SOUTH_NODE` |
| `Ayanamsa` | `LAHIRI`, `RAMAN`, `KRISHNAMURTI`, `FAGAN_BRADLEY`, `YUKTESHWAR`, `TRUE_CHITRAPAKSHA`, `TRUE_REVATI`, `TRUE_PUSHYA`, `USHASHASHI`, `SURYASIDDHANTA`, `SURYASIDDHANTA_MSUN`, `ARYABHATA`, `ARYABHATA_MSUN`, `SS_REVATI`, `SS_CITRA`, `TRUE_MULA`, `ARYABHATA_522`, `LAHIRI_1940`, `LAHIRI_VP285`, `KRISHNAMURTI_VP291`, `LAHIRI_ICRC` (each the Swiss Ephemeris mode of the same name) |
| `HouseSystem` | `WHOLE_SIGN`, `EQUAL`, `PLACIDUS`, `SRIPATI`, `KOCH`, `PORPHYRY`, `REGIOMONTANUS`, `CAMPANUS` |
| `RiseSetEvent` | `RISE`, `SET`, `UPPER_TRANSIT`, `LOWER_TRANSIT` |
| `DiscConvention` | `UPPER_LIMB_REFRACTED` (default), `CENTRE_NO_REFRACTION`, `CENTRE_REFRACTED` |
| `PairCombination` | `DIFFERENCE` (a − b), `SUM` (a + b) |
| `ChartAngle` | `ASCENDANT`, `MIDHEAVEN` |
| `EclipseKind`, `EclipseType` | `SOLAR`, `LUNAR`; `TOTAL`, `ANNULAR`, `HYBRID`, `PARTIAL`, `PENUMBRAL` |
| `Upagraha` | `DHUMA`, `VYATIPATA`, `PARIVESHA`, `INDRACHAPA`, `UPAKETU`, `KALA`, `MRITYU`, `ARDHAPRAHARA`, `YAMAGHANTAKA`, `GULIKA`, `MANDI` |
| `PortionPoint` | `PORTION_START`, `PORTION_MIDDLE`, `PORTION_END` |
| `MeanModel`, `MeanFrame` | `MODERN`, `CLASSICAL`; `REQUEST_ZODIAC`, `CLASSICAL_SIDEREAL` |

## RPC reference

All RPCs are unary except `StreamPositions`, and all are idempotent. Examples are grpcurl request JSON (lowerCamel field names, enum names as strings; grpcurl prints responses with the proto's snake_case names); the same fields exist in every generated client.

| RPC | Use it for | Cached by the server |
| --- | --- | --- |
| `GetPositions` | Positions of several bodies at several instants | No |
| `StreamPositions` | Positions at a fixed step over a range (ephemeris tables) | No |
| `GetAyanamsa` | Ayanamsa values | No |
| `GetHouses` | Ascendant, MC, ARMC, Vertex, house cusps | No |
| `GetRiseSet` | Rise, set and meridian transits | No |
| `FindCrossings` | Exact times a body crosses signs, nakshatras or any longitudes | Yes |
| `FindStations` | Exact times a body turns retrograde or direct | Yes |
| `FindPairCrossings` | Exact times the difference or sum of two longitudes crosses targets | Yes |
| `FindAngleCrossings` | Exact times the ascendant or midheaven crosses targets | Yes |
| `FindEclipses` | Solar or lunar eclipses, global or local | Yes |
| `FindReturns` | Returns of a body or pair to its natal value | Yes |
| `GetSolarTime` | Local mean and apparent time offsets | No |
| `GetUpagrahas` | Upagrahas and the portions of the day or night | No |
| `GetMeanPositions` | Mean planets and sighrocca | No |
| `GetInfo` | Version, supported range, limits, source URL | No |

### GetPositions

Returns, for every instant, one `BodyPosition` per requested body: `longitude`, `latitude`, `distanceAu`, `longitudeSpeed`, `latitudeSpeed`, `distanceSpeed`. Optional `zodiac` and `observer` (topocentric). With `"equatorial": true` every position also carries `rightAscension` and `declination`, and every instant `trueObliquity` and `nutationLongitude`. Limit: instants × bodies ≤ 10,000.

```json
{"instants": ["1990-05-17T08:30:00Z"],
 "bodies": ["SUN", "MOON", "MARS", "TRUE_NODE"],
 "zodiac": {"sidereal": {"ayanamsa": "LAHIRI"}}}
```

### StreamPositions

Samples `from`, `from + stepDays`, … up to `to` and streams them in messages of up to 500 instants, each shaped like a `GetPositions` response. Limit: samples × bodies ≤ 1,000,000 per stream.

```json
{"from": "2026-01-01T00:00:00Z", "to": "2027-01-01T00:00:00Z", "stepDays": 1,
 "bodies": ["SATURN", "JUPITER"], "zodiac": {"sidereal": {"ayanamsa": "LAHIRI"}}}
```

### GetAyanamsa

Returns one value in degrees per instant, in request order, for the given `ayanamsa`. Limit: 10,000 instants.

### GetHouses

Each query is an instant plus a location; the response holds `ascendant`, `midheaven`, `armc`, `vertex` and 12 `cusps` (house 1 first) per query. When the system is undefined at that latitude (Placidus, Koch and the like beyond the polar circles), `cuspsDefined` is false and `cusps` is empty; the angles are still valid. For `SRIPATI` the result also holds the bhavas: `bhavaMadhya` (the Porphyry cusps, `[0]` = ascendant, `[9]` = MC) and `bhavaSandhi` (the Sripati cusps, equal to `cusps`, each halfway between two madhyas). Limit: 10,000 queries.

```json
{"system": "WHOLE_SIGN", "zodiac": {"sidereal": {"ayanamsa": "LAHIRI"}},
 "queries": [{"instant": "1990-05-17T08:30:00Z",
              "location": {"latitude": 50.45, "longitude": 30.52}}]}
```

### GetRiseSet

For one `body`, each query returns the first requested events after its `after` instant at its location. A south node rises when its node sets, but only on the geometric horizon, so `MEAN_SOUTH_NODE` and `TRUE_SOUTH_NODE` take `RISE` and `SET` with `CENTRE_NO_REFRACTION` only (other conventions give `INVALID_ARGUMENT`); their transits work with any convention. `convention` defines rise and set: `UPPER_LIMB_REFRACTED` (default, the usual almanac sunrise), `CENTRE_NO_REFRACTION` (the disc centre on the true horizon, used for the Hindu sunrise) or `CENTRE_REFRACTED`. Limit: 10,000 events in total.

```json
{"body": "SUN", "convention": "CENTRE_NO_REFRACTION",
 "queries": [{"after": "2026-10-10T21:00:00Z",
              "location": {"latitude": 50.45, "longitude": 30.52},
              "events": ["RISE", "SET"]}]}
```

### FindCrossings

Returns every instant between `from` and `to` at which the body's longitude crosses a target, in time order, with the target `longitude` and `retrograde` = true when it crossed moving backwards. Targets are either a `grid` (every `offsetDegrees + k × stepDegrees` modulo 360, where `stepDegrees` must divide 360 into whole parts: 30 for signs, 13.333… for nakshatras, 3.333… for padas) or a `list` of exact longitudes (e.g. natal points). Limit: 10,000 results; a longer result fails with `INVALID_ARGUMENT`, so narrow the range.

```json
{"body": "SATURN", "from": "2026-01-01T00:00:00Z", "to": "2036-01-01T00:00:00Z",
 "zodiac": {"sidereal": {"ayanamsa": "LAHIRI"}}, "grid": {"stepDegrees": 30}}
```

### FindStations

Returns every station in the range, in time order: the instant, the longitude and `turnsRetrograde` (false = turns direct). The Sun, Moon, mean node and mean apogee never station and return an empty list. A retrograde period runs from a station with `turnsRetrograde` = true to the next one with false.

### FindPairCrossings

The value `lon(bodyA) − lon(bodyB)` (`DIFFERENCE`) or `lon(bodyA) + lon(bodyB)` (`SUM`), modulo 360, in the request's zodiac, crossing a `grid` or `list` exactly as in `FindCrossings`. Returns the crossings in time order with the target `value` and `decreasing` (the value was falling). The Moon always outruns the other body, so a pair with the Moon never reverses: a sum or a difference with the Moon as `bodyA` always increases, a difference with the Moon as `bodyB` always decreases. A difference does not depend on the zodiac; a sum contains twice the ayanamsa. The two bodies must differ, and a node with its own south node is no pair for a `DIFFERENCE` (always 180°). Limit: 10,000 results.

| Use | Request |
| --- | --- |
| Tithi ends, new moon at 0, full moon at 180 | `MOON − SUN` `DIFFERENCE`, `grid` 12 |
| Karana ends | `MOON − SUN` `DIFFERENCE`, `grid` 6 |
| Nithya yoga ends | `MOON + SUN` `SUM`, sidereal, `grid` 360/27 |
| Conjunctions and oppositions | two planets, `DIFFERENCE`, `list` [0, 180] |
| Planetary war | two planets, `DIFFERENCE`, `list` [359, 1] (entering and leaving 1°) |

```json
{"pair": {"bodyA": "MOON", "bodyB": "SUN", "combination": "DIFFERENCE"},
 "from": "2026-10-01T00:00:00Z", "to": "2026-11-01T00:00:00Z", "grid": {"stepDegrees": 12}}
```

### FindAngleCrossings

Exact instants the `ASCENDANT` or `MIDHEAVEN` of `location` crosses a `grid` or `list` in the request's zodiac (the angles of `GetHouses`, independent of any house system); the response is a `CrossingsResponse`. Both angles only move forward where the service computes them, so `retrograde` is always false. Beyond the polar circles the ascendant jumps and moves backwards, so a latitude beyond ±66.5° is `INVALID_ARGUMENT` for `ASCENDANT`; the midheaven works at any latitude. Limit: 10,000 results.

```json
{"angle": "ASCENDANT", "location": {"latitude": 50.45, "longitude": 30.52},
 "zodiac": {"sidereal": {"ayanamsa": "LAHIRI"}},
 "from": "2026-10-20T00:00:00Z", "to": "2026-10-21T00:00:00Z", "grid": {"stepDegrees": 3.3333333333333335}}
```

### FindEclipses

Solar or lunar eclipses (`kind`) whose maximum lies between `from` and `to`, in time order. Without `location` they are global; with one, only the eclipses visible there (some phase with the Sun or Moon above the horizon), with their local type and magnitude, and contacts limited to the time the body is above the horizon: a sunrise, sunset, moonrise or moonset during the eclipse becomes its `begin`, `end` or `maximum` (where the body sets and rises again during one eclipse, near the polar circles, the stretch with the maximum is kept).

- `magnitude`: solar eclipses use NASA's convention (the fraction of the Sun's diameter covered for a partial eclipse, the ratio of the apparent diameters for a total, annular or hybrid one); lunar eclipses the umbral magnitude, or the penumbral magnitude for a penumbral eclipse.
- `begin`, `end`: penumbral contacts for a penumbral lunar eclipse, partial-phase contacts otherwise; for a global solar eclipse, the first and last contact of the penumbra with the Earth. `totalityBegin`, `totalityEnd`: the total or annular phase, when there is one.
- `HYBRID` is only reported globally; seen from a place, a hybrid eclipse is total or annular.

```json
{"kind": "SOLAR", "from": "2026-01-01T00:00:00Z", "to": "2027-01-01T00:00:00Z",
 "location": {"latitude": 43.36, "longitude": -5.85}}
```

### FindReturns

Every instant between `from` and `to` at which a `body`, or a `pair` as in `FindPairCrossings`, returns to its value at `natal`, in the request's zodiac. The response holds that `natalValue` and the `returns` in time order, with `backwards` when the value was falling (a retrograde body). The natal instant itself is never reported. Solar return: `body` `SUN`; sidereal (Tajaka) solar return: the same with a sidereal zodiac; tithi pravesha: `pair` `MOON − SUN`, of which the client picks the return near the solar return. Limit: 10,000 results.

```json
{"body": "SUN", "natal": "1994-04-23T04:44:00Z", "zodiac": {"sidereal": {"ayanamsa": "LAHIRI"}},
 "from": "2026-01-01T00:00:00Z", "to": "2027-01-01T00:00:00Z"}
```

### GetSolarTime

For each query (an instant at a place): `localMeanOffset` = longitude × 4 minutes, `equationOfTime` = apparent minus mean solar time at that instant (about −14 to +16 minutes over a year), `localApparentOffset` = their sum. Local mean time is UTC + `localMeanOffset`, local apparent time UTC + `localApparentOffset`; apparent noon itself is `GetRiseSet` `UPPER_TRANSIT` of the Sun. Limit: 10,000 queries.

### GetUpagrahas

For each query (an instant at a place), in the request's zodiac:

- **Dhuma group** from the Sun (BPHS 3.61–64): Dhuma = Sun + 133°20′, Vyatipata = 360° − Dhuma, Parivesha = Vyatipata + 180°, Indrachapa = 360° − Parivesha, Upaketu = Indrachapa + 16°40′.
- **Day and portions:** the day (sunrise to sunset) or night (sunset to the next sunrise) containing the instant, split into 8 equal `portions`. The Vedic day starts at sunrise, so an instant before sunrise belongs to the previous day; `weekdayLord` is the lord of the local mean date of that sunrise. Day portions start from the weekday lord, night portions from the fifth lord counted from it (Sunday night from Jupiter); both go on in weekday order, and the eighth has no lord. `sunrise` defines sunrise and sunset (`CENTRE_NO_REFRACTION` for the Hindu sunrise).
- **Kala group** (BPHS 3.66–70): the ascendant at `kalaPoint` (default start) of the portion of the Sun (Kala), Mars (Mrityu), Mercury (Ardhaprahara), Jupiter (Yamaghantaka) and Saturn (Gulika). Mandi equals Gulika unless `mandiPoint` places it elsewhere in Saturn's portion.
- **Polar day or night:** `portions` is empty and the Kala group absent; `night` tells whether the Sun is below the horizon and `weekdayLord` follows the local mean date of the instant.

Limit: queries × 19 ≤ 10,000 (526 queries), since every query returns 19 items: 11 upagrahas and 8 portions.

```json
{"queries": [{"instant": "2026-10-20T09:00:00Z", "location": {"latitude": 50.45, "longitude": 30.52}}],
 "zodiac": {"sidereal": {"ayanamsa": "LAHIRI"}}, "sunrise": "CENTRE_NO_REFRACTION"}
```

### GetMeanPositions

Mean (madhya) longitudes and sighrocca of `SUN`, `MOON`, `MERCURY`, `VENUS`, `MARS`, `JUPITER` and `SATURN` (other bodies give `INVALID_ARGUMENT`): for Mars, Jupiter and Saturn the heliocentric mean longitude, with the mean Sun as sighrocca; for Mercury and Venus the mean Sun, with their heliocentric mean longitude as sighrocca; for the Sun and the Moon their mean longitudes, without sighrocca. The `MODERN` model uses the mean elements of Simon et al. (1994) and, for the Moon, Meeus's equation 47.1, referred to the mean equinox of date and shifted by the ayanamsa without nutation for a sidereal zodiac; the response's `frame` is then `REQUEST_ZODIAC`. The `CLASSICAL` model (since 0.2.1; `UNIMPLEMENTED` in 0.2.0) gives the ahargana-based mean planets of B. V. Raman's "Graha and Bhava Balas" (chapter VI, tables I–IX) in the book's own sidereal frame: `frame` is `CLASSICAL_SIDEREAL`, the request's `zodiac` is ignored, and no ayanamsa converts it (the classical mean Sun of 1918-10-16 is 1.56° behind the `MODERN` one with `RAMAN`). Each value is L0 + n·D + c(t), with D the days since 0h on 1 January 1900 in Ujjain mean time; the constants reproduce the book's daily motions and its worked example (its Standard Horoscope) to within 0.07°. The book has no classical mean Moon, so a `CLASSICAL` request with `MOON` is `INVALID_ARGUMENT`. Limit: instants × bodies ≤ 10,000.

### GetInfo

Takes an empty request and returns `engineVersion`, `supportedFrom`, `supportedTo`, `maxItemsPerRequest`, `maxStreamItems`, `sourceUrl` and `serviceVersion` (the running release; local builds report their build version or `dev`). Read the range and limit from here instead of hard-coding them.

## Errors and limits

Errors are standard gRPC status codes with a human-readable description; only `UNAVAILABLE`, `RESOURCE_EXHAUSTED` and `DEADLINE_EXCEEDED` are worth retrying.

| Status | When | What to do |
| --- | --- | --- |
| `INVALID_ARGUMENT` | Missing or unspecified field, bad coordinates, a grid step that does not divide 360, a limit exceeded, too many search results | Fix the request; do not retry |
| `OUT_OF_RANGE` | An instant outside the supported range (`GetInfo`) | Clamp or reject on your side; do not retry |
| `INTERNAL` | An engine failure that should not happen | Report it with the request; retry once at most |
| `UNIMPLEMENTED` | A part of the contract an older release does not serve (`GetMeanPositions` with `CLASSICAL` before 0.2.1, the 0.2 RPCs before 0.2.0) | Do not retry; fall back or upgrade the service |
| `RESOURCE_EXHAUSTED` | Too many requests are already waiting for a calculation worker | Retry with exponential backoff |
| `UNAVAILABLE` | Network problem or the service restarting | Retry with exponential backoff; every RPC is idempotent |
| `DEADLINE_EXCEEDED` | Your deadline passed | Retry with a longer deadline or a narrower range |

| Limit | Default |
| --- | --- |
| Instants × bodies (`GetPositions`), instants (`GetAyanamsa`), queries (`GetHouses`), events (`GetRiseSet`) | 10,000 per request |
| Results of `FindCrossings`, `FindStations`, `FindPairCrossings`, `FindAngleCrossings`, `FindEclipses`, `FindReturns`; longitudes in a `list` | 10,000 per request |
| Queries of `GetSolarTime`; instants × bodies of `GetMeanPositions` | 10,000 per request |
| Queries × 19 of `GetUpagrahas` (each returns 11 upagrahas and 8 portions) | 10,000 per request (526 queries) |
| Samples × bodies of one `StreamPositions` | 1,000,000 |
| Message size | 4 MiB (gRPC default), well above the largest response the limits allow |

Each deployment can change these limits; `GetInfo` reports the current ones in `maxItemsPerRequest` and `maxStreamItems`.

## Integration patterns

Batch what you can: one call with many instants or bodies costs about as much as a single-body call, while every extra call adds a network round trip.

- **Natal chart:** one `GetPositions` (the birth instant, all bodies including `TRUE_SOUTH_NODE` or `MEAN_SOUTH_NODE` for Ketu, your zodiac) plus one `GetHouses` (same instant and zodiac, your house system), sent concurrently on the same channel. The lagna is `ascendant`.
- **Several charts at once** (synastry, a family): put all birth instants in one `GetPositions` and all queries in one `GetHouses`.
- **Sunrise-based day data:** `GetRiseSet` for the Sun with `CENTRE_NO_REFRACTION`, then `GetPositions` at the returned sunrise.
- **Transits by sign or nakshatra:** `FindCrossings` with a `grid` (30° signs, 360°/27 nakshatras, 360°/108 padas). This replaces sampling positions and comparing them yourself.
- **Transits over natal points:** `FindCrossings` with a `list` of the natal longitudes, in the same zodiac as the natal chart.
- **Retrograde periods:** `FindStations`; pair each `turnsRetrograde = true` with the next `false`.
- **Panchanga boundaries:** `FindPairCrossings` for the ends of tithis, karanas and nithya yogas, and new and full moons; `FindCrossings` of the Moon over 360°/27 for nakshatras.
- **Lagna and muhurta windows, birth-time rectification:** `FindAngleCrossings` of the ascendant over signs (30°), navamsas (360°/108) or any division.
- **Eclipses near an event:** `FindEclipses` with the place, so only visible eclipses come back.
- **Returns:** `FindReturns` instead of looking up the natal value and searching for it yourself.
- **Ephemeris tables and charts over time:** `StreamPositions`, consuming messages as they arrive.

Operational advice:

- Create **one long-lived channel** per process and share it; channels multiplex calls and reconnect on their own.
- Set a **deadline on every call**: about 2 s for positions, houses and rise/set; 30 s for searches over long ranges.
- **Cache freely on your side:** a response depends only on its request, so equal requests always get equal responses. The service already caches `FindCrossings` and `FindStations` results in memory.
- **Check the range first:** call `GetInfo` at startup and reject out-of-range dates before calling.

## Accuracy and known limits

Positions match the reference `swetest` tool built from the same sources to 1e-6°, and search instants are refined to about 0.1 s.

- **Engine:** Swiss Ephemeris 2.10.03 with its DE441-based `.se1` files. The service refuses to start without them rather than fall back to the less precise Moshier model.
- **Time scale:** UTC is used as UT1 (under 0.9 s apart); the engine applies ΔT itself.
- **Stations of slow bodies** are only defined to about a second, because the body barely moves then.
- **True node:** stations closer than 0.1 day apart are not resolved, and crossing searches skip wobbles smaller than 0.014°. Over 1900–2100 this missed none of the sign, nakshatra or pada crossings.
- **Neptune and Pluto** occasionally station two or three times within an hour (moving under 0.5″); such a cluster is reported as one station.
- **House systems** that are undefined beyond the polar circles report `cuspsDefined = false` instead of substitute cusps.
- **Rise and set** use a standard atmosphere for refraction; pick `CENTRE_NO_REFRACTION` when you need the Hindu sunrise.
- **Pair searches** find the same instants as sampling every 0.02 day for the fast pairs tested over 2000–2040; where two slow planets' speeds barely touch, a loop of a few arcseconds (up to 4″ over 1900–2100) may be skipped.
- **Angle searches** are exact to about 0.1 s; near the polar circles the ascendant moves up to about a degree per second, so its longitude at a reported instant can differ from the target by a few hundredths of a degree.
- **Eclipses:** over 2026 the maxima, magnitudes and lunar contacts agree with NASA's catalogue to about a minute (the catalogues use slightly different ΔT). The grazing partial solar eclipse of 1935-01-05 (magnitude 0.0013) is reported with magnitude 0, the only such case over 1800–2400.
- **Mean positions** (`MODERN`) follow published polynomials to their T² terms; over 1900–2100 the mean Sun agrees with Meeus's L0 to 1e-4°. `CLASSICAL` reproduces Raman's worked example to 0.02° for the Sun, Mars, Saturn and the sighrocca of Venus, 0.03° for that of Mercury, and 0.07° for Jupiter, where the book's example uses a misprinted table cell.

## Licensing and versioning

The service is open source under the GNU AGPL v3.0 or later; the contract is versioned by its package name.

- **License:** the service and the Swiss Ephemeris it uses (© Astrodienst AG) are under the AGPL. Calling the service over the network from your own program is generally treated as communication between separate programs. If you modify the service and run it for others, section 13 of the AGPL requires offering them its source. This is not legal advice.
- **Compatibility within `v1`:** only additions — new fields, RPCs and enum values. Generated clients ignore unknown fields; handle unknown enum values without failing.
- **Breaking changes** get a new package (`me.lagna.ephemeris.v2`) served alongside `v1` for a transition period.
- **Pin the contract:** generate your client from a tagged commit of the proto and regenerate when you upgrade.
