package aegis.networklab;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;

/** Extract the CN RDN from a certificate subject (lab identity check: CN must equal serverId). */
public final class CertIdentity {
  private CertIdentity() {}

  public static String commonName(X509Certificate cert) {
    return cnFromRfc2253(cert.getSubjectX500Principal().getName(X500Principal.RFC2253));
  }

  static String cnFromRfc2253(String dn) {
    StringBuilder cur = new StringBuilder();
    boolean escaped = false;
    for (int i = 0; i < dn.length(); i++) {
      char c = dn.charAt(i);
      if (escaped) { cur.append(c); escaped = false; continue; }
      if (c == '\\') { escaped = true; continue; } // unescape: \, → ,
      if (c == ',' || c == '+') {
        String cn = matchCn(cur.toString());
        if (cn != null) return cn;
        cur.setLength(0);
        continue;
      }
      cur.append(c);
    }
    return matchCn(cur.toString());
  }

  private static String matchCn(String rdn) {
    String t = rdn.trim();
    if (t.regionMatches(true, 0, "CN=", 0, 3)) return t.substring(3).trim();
    return null;
  }
}
