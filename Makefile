# make test                      the server's tests
# make release VERSION=v1.2.3    dist/devotion-v1.2.3-linux-amd64.tar.gz (server + its own Node.js)
VERSION ?= v0.0.0-dev
NODE_VERSION ?= 24.22.3
NAME := devotion-$(VERSION)-linux-amd64
STAGE := dist/$(NAME)

.PHONY: test release clean

test:
	cd server && npm ci --no-audit --no-fund && node --test

release:
	rm -rf $(STAGE) dist/$(NAME).tar.gz
	mkdir -p $(STAGE)/server dist/cache
	cp -R server/src server/package.json server/package-lock.json $(STAGE)/server/
	cd $(STAGE)/server && npm ci --omit=dev --no-audit --no-fund
	cp -R deploy $(STAGE)/deploy
	echo "$(VERSION)" > $(STAGE)/VERSION
	# The runtime ships with the release, so the Node.js version is ours and not the distribution's.
	cd dist/cache && curl -fsSLO --retry 3 https://nodejs.org/dist/v$(NODE_VERSION)/node-v$(NODE_VERSION)-linux-x64.tar.xz \
		&& curl -fsSL --retry 3 https://nodejs.org/dist/v$(NODE_VERSION)/SHASUMS256.txt | grep ' node-v$(NODE_VERSION)-linux-x64.tar.xz$$' | sha256sum -c -
	mkdir -p $(STAGE)/node/bin
	tar -xJf dist/cache/node-v$(NODE_VERSION)-linux-x64.tar.xz -C $(STAGE)/node --strip-components=1 node-v$(NODE_VERSION)-linux-x64/bin/node node-v$(NODE_VERSION)-linux-x64/LICENSE
	$(STAGE)/node/bin/node --version
	tar -czf dist/$(NAME).tar.gz --owner=0 --group=0 -C dist $(NAME)
	rm -rf $(STAGE)
	@echo "dist/$(NAME).tar.gz"

clean:
	rm -rf dist
