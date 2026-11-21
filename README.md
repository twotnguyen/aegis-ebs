# AEGIS-EBS

Emergency Broadcast System — CMP180 capstone. SIMULATION/TRAINING only.

## Build
```
brew install maven        # Node already present for dashboard
./scripts/dev.sh          # mvn package
./scripts/test.sh         # mvn test
./scripts/initdb.sh       # create aegis.db (26 tables)
```
Modules: aegis-protocol, aegis-core, aegis-persistence, aegis-server, aegis-client, aegis-simulator, aegis-network-lab. Dashboard (web+Three.js) in aegis-dashboard/ (Node).

See docs/PROTOCOL.md for EBSP/2.0 wire contract. Report follows HUTECH MauBCMH structure.
