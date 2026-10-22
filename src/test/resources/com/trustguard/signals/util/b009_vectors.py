"""B-009 pinned vectors, computed independently of the Java code (stdlib only).

Paste the four printed values into AnonymousActorIdTest and IdempotencyKeyTest.
The master key is the 32 bytes 0x00..0x1f, as in the Java tests.
"""
import hashlib
import hmac
import struct

MASTER_KEY = bytes(range(32))
TENANT_ID = "123e4567-e89b-12d3-a456-426614174000"
SESSION_ASCII = "session-abc"
SESSION_UNICODE = "s\u00e9ssion-\u2713"
ACTOR_ID = "user-42"
EVENT_TYPE = "LOGIN"
OCCURRED_AT = "2026-01-02T03:04:05.123Z"
CANONICAL_PAYLOAD = '{"a":1,"b":[true,null],"c":"x"}'
CANONICAL_EMPTY = "{}"


def subkey(label):
    return hmac.new(MASTER_KEY, label.encode("utf-8"), hashlib.sha256).digest()


def anon_id(session_id):
    message = (TENANT_ID + session_id).encode("utf-8")
    mac = hmac.new(subkey("trustguard.anon-actor-id.v1"), message, hashlib.sha256)
    return "anon_" + mac.hexdigest()


def length_prefixed(fields):
    out = b""
    for field in fields:
        raw = field.encode("utf-8")
        out += struct.pack(">I", len(raw)) + raw
    return out


def idempotency_key(canonical_payload):
    payload_hash = hashlib.sha256(canonical_payload.encode("utf-8")).hexdigest()
    message = length_prefixed([TENANT_ID, ACTOR_ID, EVENT_TYPE, OCCURRED_AT, payload_hash])
    return hmac.new(subkey("trustguard.idempotency-key.v1"), message, hashlib.sha256).hexdigest()


print("ANON_ASCII          =", anon_id(SESSION_ASCII))
print("ANON_UNICODE        =", anon_id(SESSION_UNICODE))
print("IDEM_WITH_PAYLOAD   =", idempotency_key(CANONICAL_PAYLOAD))
print("IDEM_EMPTY_PAYLOAD  =", idempotency_key(CANONICAL_EMPTY))