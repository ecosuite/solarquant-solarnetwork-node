# SolarQuant Script Installation

The `solarquant.sh` script manages Docker containers for SolarQuant plugins.
It must be installed on the SolarNode host where Docker is available.

## Installation

Copy the script to the SolarNode bin directory:

```bash
sudo cp solarquant.sh /opt/solarnode/bin/solarquant
sudo chmod +x /opt/solarnode/bin/solarquant
```

## Prerequisites

- Docker must be installed and the `solar` user must be able to run `docker`
  commands (e.g. member of the `docker` group).

## Usage

```bash
# Pull image and start container; prints allocated host port to stdout
solarquant start ...

# Check container status
solarquant status solarquant-myuid

# Stop and remove container
solarquant stop solarquant-myuid
```
