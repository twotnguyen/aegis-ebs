#!/usr/bin/env bash
# Generates the self-signed TLS identity for the AEGIS EBSP gateway (port 9443)
# plus a truststore clients can use to verify it. Demo/lab use only.
#
# Usage: scripts/gen-server-cert.sh [output-dir] [store-password]
#   output-dir     default: certs/
#   store-password default: aegis-demo
#
# Produces:
#   <dir>/aegis-server.p12      PKCS12 keystore (server private key + cert, SAN=localhost,127.0.0.1)
#   <dir>/aegis-server.crt      PEM certificate (for clients / truststore import)
#   <dir>/aegis-truststore.p12  PKCS12 truststore containing only the server cert
set -euo pipefail
cd "$(dirname "$0")/.."
DIR="${1:-certs}"
PASS="${2:-aegis-demo}"
mkdir -p "$DIR"

if [[ -f "$DIR/aegis-server.p12" ]]; then
  echo "EXISTS $DIR/aegis-server.p12 (delete it to regenerate)"
else
  keytool -genkeypair \
    -alias aegis-server \
    -keyalg RSA -keysize 2048 -validity 365 \
    -dname "CN=localhost, OU=AEGIS-EBS, O=CMP180, L=HoChiMinh, C=VN" \
    -ext "SAN=dns:localhost,ip:127.0.0.1" \
    -keystore "$DIR/aegis-server.p12" -storetype PKCS12 \
    -storepass "$PASS" -keypass "$PASS"
  echo "CREATED $DIR/aegis-server.p12"
fi

keytool -exportcert -alias aegis-server \
  -keystore "$DIR/aegis-server.p12" -storetype PKCS12 -storepass "$PASS" \
  -rfc -file "$DIR/aegis-server.crt"

rm -f "$DIR/aegis-truststore.p12"
keytool -importcert -noprompt -alias aegis-server \
  -file "$DIR/aegis-server.crt" \
  -keystore "$DIR/aegis-truststore.p12" -storetype PKCS12 -storepass "$PASS"

echo "TRUSTSTORE $DIR/aegis-truststore.p12"
echo "Run server: mvn -q -pl aegis-server exec:java -Dexec.mainClass=aegis.server.ServerMain \\"
echo "  -Dexec.args=\"--keystore $DIR/aegis-server.p12 --storepass $PASS\""
