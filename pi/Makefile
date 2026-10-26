# dist/ is everything a Pi needs: copy it over and run sudo ./install.sh
LDFLAGS := -s -w
GOBUILD := CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="$(LDFLAGS)"

.PHONY: dist test preview qemu clean

dist:
	mkdir -p dist
	GOARCH=arm64 $(GOBUILD) -o dist/icloud-frame-linux-arm64 .
	GOARCH=arm GOARM=7 $(GOBUILD) -o dist/icloud-frame-linux-armv7 .
	cp install/* dist/

test:
	go test ./...

# Mac window at the panel's resolution, e.g. make preview ARGS="-size 800x480 -interval 10s"
preview:
	set -a; [ -f ../.env ] && . ../.env; set +a; go run . $(ARGS)

# Boots Raspberry Pi OS in an emulated Pi 3A+ (same chip and RAM as a Zero 2 W)
qemu: dist
	./qemu/run.sh

clean:
	rm -rf dist frames
