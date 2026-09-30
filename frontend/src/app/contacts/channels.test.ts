// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { asciiDigit, mailtoHref, telHref, webHref } from "./channels";

describe("mailtoHref", () => {
  it("links an ordinary address", () => {
    expect(mailtoHref("ada@example.org")).toBe("mailto:ada@example.org");
  });

  it("percent-encodes both parts, so a stored value cannot add headers", () => {
    const href = mailtoHref("a@b?bcc=x");
    expect(href).toBe("mailto:a@b%3Fbcc%3Dx");
    expect(mailtoHref("a@b&body=hi")).toBe("mailto:a@b%26body%3Dhi");
    expect(mailtoHref("a?cc=evil@b.example")).toBe("mailto:a%3Fcc%3Devil@b.example");
    expect(mailtoHref("a@b#x")).toBe("mailto:a@b%23x");
    for (const address of ["a@b?bcc=x", "a@b&body=hi", "a?cc=evil@b.example", "a@b#x", "a%0Ab@c"]) {
      const encoded = mailtoHref(address)?.slice("mailto:".length) ?? "";
      expect(encoded).not.toMatch(/[?&#=]/);
      expect(decodeURIComponent(encoded)).toBe(address);
    }
  });

  it("keeps quoted local parts with an @ by splitting at the last one", () => {
    expect(mailtoHref('"a@b"@example.org')).toBe("mailto:%22a%40b%22@example.org");
  });

  it("links no value that is not an address", () => {
    expect(mailtoHref("ada")).toBeUndefined();
    expect(mailtoHref("@example.org")).toBeUndefined();
    expect(mailtoHref("ada@")).toBeUndefined();
    expect(mailtoHref("ada @example.org")).toBeUndefined();
    expect(mailtoHref("javascript:alert(1)//@x")).toBe("mailto:javascript%3Aalert(1)%2F%2F@x");
  });
});

describe("telHref", () => {
  it("keeps only + digits * # , ; and encodes #", () => {
    expect(telHref("+49 (30) 123-456")).toBe("tel:+4930123456");
    expect(telHref("030 123,45;ext=9")).toBe("tel:030123,45;9");
    expect(telHref("*31#")).toBe("tel:*31%23");
  });

  it("maps digits of other scripts to ASCII", () => {
    expect(telHref("+٤٩ ٣٠ ١٢٣")).toBe("tel:+4930123"); // Arabic-Indic
    expect(telHref("۰۹۱۲")).toBe("tel:0912"); // Extended Arabic-Indic (Persian)
    expect(telHref("०९८७")).toBe("tel:0987"); // Devanagari
    expect(telHref("＋４９ ３０")).toBe("tel:+4930"); // full-width, folded by NFKC
  });

  it("drops anything that could leave the number: letters, URL syntax, markup", () => {
    expect(telHref("1-800-FLOWERS")).toBe("tel:1800");
    expect(telHref("123?x=1&y=<script>")).toBe("tel:1231");
    expect(telHref("javascript:alert(1)")).toBe("tel:1");
  });

  it("links no value without a digit", () => {
    expect(telHref("call me")).toBeUndefined();
    expect(telHref("+#")).toBeUndefined();
  });
});

describe("asciiDigit", () => {
  it("reads the value of a decimal digit of any script", () => {
    expect(asciiDigit("7")).toBe("7");
    expect(asciiDigit("٣")).toBe("3");
    expect(asciiDigit("৫")).toBe("5"); // Bengali
    expect(asciiDigit("๙")).toBe("9"); // Thai
    expect(asciiDigit("𝟠")).toBe("8"); // mathematical double-struck, inside a run of five digit sets
    expect(asciiDigit("𝟿")).toBe("9"); // the last of that run
  });

  it("refuses anything that is not a decimal digit", () => {
    expect(asciiDigit("a")).toBeUndefined();
    expect(asciiDigit("½")).toBeUndefined();
    expect(asciiDigit("Ⅻ")).toBeUndefined();
  });
});

describe("webHref", () => {
  it("links absolute http(s) addresses only", () => {
    expect(webHref("https://www.linkedin.com/in/ada")).toBe("https://www.linkedin.com/in/ada");
    expect(webHref("http://ada.example")).toBe("http://ada.example");
    expect(webHref("javascript:alert(1)")).toBeUndefined();
    expect(webHref("data:text/html,<script>")).toBeUndefined();
    expect(webHref("ada.example")).toBeUndefined();
    expect(webHref("//ada.example")).toBeUndefined();
  });
});
