# Xeno PS4 Browser - OpenOrbis v0.5.4
TITLE       := Xeno PS4 Browser
VERSION     := 1.20
TITLE_ID    := XENO00001
CONTENT_ID  := IV0000-XENO00001_00-XENOPS4BROWSER01

TOOLCHAIN   := $(OO_PS4_TOOLCHAIN)
PROJDIR     := source
INTDIR      := build
CDIR        := linux
UNAME_S     := $(shell uname -s)
ifeq ($(UNAME_S),Darwin)
CDIR        := macos
endif

CC          := clang
CXX         := clang++
LD          := ld.lld
ifeq ($(UNAME_S),Darwin)
CC          := /usr/local/opt/llvm/bin/clang
CXX         := /usr/local/opt/llvm/bin/clang++
LD          := /usr/local/opt/llvm/bin/ld.lld
endif

LIBS := -lc -lkernel -lc++ \
        -lSceUserService -lSceSystemService -lSceVideoOut -lSceAudioOut \
        -lScePad -lSceSysmodule -lSceNet -lSceSsl -lSceHttp \
        -lSceCommonDialog -lSceImeDialog -lSceWebBrowserDialog -lSDL2

CFLAGS   := --target=x86_64-pc-freebsd12-elf -fPIC -funwind-tables -c \
            -Wall -Wno-unused-function -O2 -DORBIS -D_GNU_SOURCE \
            -isysroot $(TOOLCHAIN) -isystem $(TOOLCHAIN)/include
CXXFLAGS := $(CFLAGS) -std=c++17 -isystem $(TOOLCHAIN)/include/c++/v1
LDFLAGS  := -m elf_x86_64 -pie --script $(TOOLCHAIN)/link.x --eh-frame-hdr \
            -L$(TOOLCHAIN)/lib $(LIBS) $(TOOLCHAIN)/lib/crt1.o

CPPFILES := $(wildcard $(PROJDIR)/*.cpp)
CFILES   := $(wildcard $(PROJDIR)/*.c)
OBJS     := $(patsubst $(PROJDIR)/%.cpp,$(INTDIR)/%.o,$(CPPFILES)) \
            $(patsubst $(PROJDIR)/%.c,$(INTDIR)/%.o,$(CFILES))

MODULE_DATA := $(TOOLCHAIN)/src/modules
RUNTIME_MODULES := sce_module/libSceFios2.prx sce_module/libc.prx
PACKAGE_FILES := eboot.bin sce_sys/about/right.sprx sce_sys/param.sfo sce_sys/icon0.png $(RUNTIME_MODULES)

.PHONY: all clean prepare
all: $(CONTENT_ID).pkg

prepare: sce_sys/about/right.sprx $(RUNTIME_MODULES)

$(INTDIR):
	mkdir -p $(INTDIR)

$(INTDIR)/%.o: $(PROJDIR)/%.cpp | $(INTDIR)
	$(CXX) $(CXXFLAGS) -o $@ $<

$(INTDIR)/%.o: $(PROJDIR)/%.c | $(INTDIR)
	$(CC) $(CFLAGS) -o $@ $<

eboot.bin: $(OBJS)
	$(LD) $(OBJS) -o $(INTDIR)/xeno_browser.elf $(LDFLAGS)
	$(TOOLCHAIN)/bin/$(CDIR)/create-fself -in=$(INTDIR)/xeno_browser.elf \
		-out=$(INTDIR)/xeno_browser.oelf --eboot eboot.bin --paid 0x3800000000000011

sce_sys/about/right.sprx:
	mkdir -p sce_sys/about
	cp $(MODULE_DATA)/right.sprx $@

sce_module/libSceFios2.prx:
	mkdir -p sce_module
	cp $(MODULE_DATA)/libSceFios2.prx $@

sce_module/libc.prx:
	mkdir -p sce_module
	cp $(MODULE_DATA)/libc.prx $@

sce_sys/param.sfo: Makefile
	mkdir -p sce_sys
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_new $@
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ APP_TYPE --type Integer --maxsize 4 --value 1
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ APP_VER --type Utf8 --maxsize 8 --value '$(VERSION)'
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ ATTRIBUTE --type Integer --maxsize 4 --value 0
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ CATEGORY --type Utf8 --maxsize 4 --value 'gd'
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ CONTENT_ID --type Utf8 --maxsize 48 --value '$(CONTENT_ID)'
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ DOWNLOAD_DATA_SIZE --type Integer --maxsize 4 --value 0
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ SYSTEM_VER --type Integer --maxsize 4 --value 0
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ TITLE --type Utf8 --maxsize 128 --value '$(TITLE)'
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ TITLE_ID --type Utf8 --maxsize 12 --value '$(TITLE_ID)'
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core sfo_setentry $@ VERSION --type Utf8 --maxsize 8 --value '$(VERSION)'

pkg.gp4: prepare eboot.bin sce_sys/param.sfo sce_sys/icon0.png
	$(TOOLCHAIN)/bin/$(CDIR)/create-gp4 -out $@ --content-id=$(CONTENT_ID) --files "$(PACKAGE_FILES)"

$(CONTENT_ID).pkg: pkg.gp4
	$(TOOLCHAIN)/bin/$(CDIR)/PkgTool.Core pkg_build $< .

clean:
	rm -rf $(INTDIR) eboot.bin pkg.gp4 $(CONTENT_ID).pkg sce_sys/param.sfo sce_sys/about sce_module
