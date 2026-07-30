package com.example.demo.ilink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IlinkAccountProfileStoreTests {
	@TempDir
	Path temporaryDirectory;

	@Test
	void shouldPersistNormalizedDisplayNamesAndRemoveThemWithAccount() {
		Path credentials = temporaryDirectory.resolve("credentials.json");
		IlinkAccountProfileStore store = new IlinkAccountProfileStore(credentials.toString());

		assertEquals("客服 一号", store.updateDisplayName("account-a", "  客服   一号  "));
		assertEquals("客服 一号", new IlinkAccountProfileStore(credentials.toString()).displayName("account-a"));

		store.delete("account-a");
		assertEquals("", new IlinkAccountProfileStore(credentials.toString()).displayName("account-a"));
	}

	@Test
	void shouldRejectOverlongDisplayNames() {
		IlinkAccountProfileStore store = new IlinkAccountProfileStore(temporaryDirectory.resolve("credentials.json").toString());
		assertThrows(IllegalArgumentException.class, () -> store.updateDisplayName("account-a", "a".repeat(41)));
	}
}
