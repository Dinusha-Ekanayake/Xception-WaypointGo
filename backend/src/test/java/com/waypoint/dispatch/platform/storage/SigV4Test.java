package com.waypoint.dispatch.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AWS's own example: "Example: GET Object" in the S3 Signature Version 4 header documentation. */
class SigV4Test {

  @Test
  void theSignatureMatchesAwssPublishedGetObjectExample() {
    String emptySha = SigV4.hex(SigV4.sha256(new byte[0]));
    Map<String, String> headers = SigV4.sign(
        "GET", "examplebucket.s3.amazonaws.com", "/test.txt", "", Map.of("Range", "bytes=0-9"), emptySha,
        "us-east-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC));
    assertEquals(
        "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
            + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
            + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
        headers.get("authorization"));
  }
}
