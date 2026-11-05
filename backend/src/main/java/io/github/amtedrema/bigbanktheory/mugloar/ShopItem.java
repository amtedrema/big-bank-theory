package io.github.amtedrema.bigbanktheory.mugloar;

public record ShopItem(String id, String name, long cost) {

	public ShopItem {
		MugloarException.required(id, "id");
		MugloarException.required(name, "name");
	}
}
