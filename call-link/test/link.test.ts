import { test } from "node:test";
import assert from "node:assert/strict";
import { decodeKey, fragmentParams, roomOf } from "../web/link.ts";

test("the call's name is derived from the key the same way as in the app (HttpLinkCallsTest)", async () => {
  const key = decodeKey("AQEBAQEBAQEBAQEBAQEBAQ");
  assert.ok(key);
  assert.equal(await roomOf(key), "zIzUHO-QfE0hYGkSLEuJk2");
});

test("only a 16-byte base64url key is accepted", () => {
  assert.equal(decodeKey("AQEBAQEBAQEBAQEBAQEBAQ")?.length, 16);
  assert.equal(decodeKey("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"), null);
  assert.equal(decodeKey(""), null);
  assert.equal(decodeKey("not base64!"), null);
});

test("an invite's fragment is the bare key; the app's has t, k and v", () => {
  assert.equal(fragmentParams("#AQEBAQEBAQEBAQEBAQEBAQ").get("k"), "AQEBAQEBAQEBAQEBAQEBAQ");
  const app = fragmentParams("#t=tok&k=AQEBAQEBAQEBAQEBAQEBAQ&v=1");
  assert.equal(app.get("t"), "tok");
  assert.equal(app.get("k"), "AQEBAQEBAQEBAQEBAQEBAQ");
  assert.equal(app.get("v"), "1");
});
