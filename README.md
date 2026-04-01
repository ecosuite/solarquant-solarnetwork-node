# SolarQuant Plugin

SolarNode plugin that buffers datums from the datum pipeline and forwards them to a SolarQuant HTTP service via `POST /measure`. Predictions returned by the service are posted back to SolarNetwork as new datums.

Implements the [SolarQuant plugin schema](https://github.com/ecosuite/solarquant-plugin). Any container exposing `/measure` and `/health` works.

## Building

Requires Java 17, Apache Ant, and Git LFS. Clone [solarnetwork-build](https://github.com/SolarNetwork/solarnetwork-build) as a sibling directory and run `git lfs pull` in it.

```
parent/
  solarnetwork-build/
  solarnetwork-node/
    solarquant/
```

```bash
cd solarquant
ant jar
```

Output: `target/net.solarnetwork.node.datum.solarquant-1.0.0.jar`

## Configuration

Add a **SolarQuant Anomaly Detection** component in the SolarNode Settings UI.

| Setting | Default | Description |
|---------|---------|-------------|
| Docker Image | *(empty)* | Image to pull and run. Leave empty to use an existing service. |
| Service URL | `http://localhost:8000` | Base URL of the SolarQuant service. Auto-set when Docker Image is configured. |
| Source ID Filter | `.*` | Regex matching source IDs to forward. |
| Upload Source ID | `/solarquant` | Base source ID for predictions. `sourceIndex` is appended (e.g. `/solarquant/1`). |
| Flush Interval | `60` s | How often buffered datums are sent. |
| Connection Timeout | `5000` ms | HTTP connection timeout. |
| Read Timeout | `30000` ms | HTTP read timeout. |
| Docker Command | `/opt/solarnode/bin/solarquant` | Path to the Docker management script. |

The plugin calls an external script to manage containers. Install `def/solarquant.sh` to the SolarNode bin directory -- see `def/README.md`.

## Protocol

See the [SolarQuant plugin schema](https://github.com/ecosuite/solarquant-plugin) for the full OpenAPI spec.

`POST /measure` -- send datums, receive predictions. `GET /health` -- liveness check (shown in SolarNode dashboard).

Datum fields map to SolarNetwork sample types: `i` (instantaneous), `a` (accumulating), `s` (status).

## Reference

- [SolarQuant plugin schema](https://github.com/ecosuite/solarquant-plugin) -- OpenAPI spec and container contract

## License

Apache License 2.0
