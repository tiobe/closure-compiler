.PHONY: all build zip clean rebuild

PREFIX:=closure-compiler
VERSION:=$(shell date +"%Y%m%d")
NAME:=$(PREFIX)-$(VERSION)
JARNAME:=compiler.jar
TARGETJAR:=./target/$(PREFIX)-1.0-SNAPSHOT.jar
RELVERSION:=v$(VERSION) TIOBE edition
BT:=mvn

all: build zip

build: $(TARGETJAR)
	java -jar $(TARGETJAR) --version

$(TARGETJAR):
	$(BT) package -Dmaven.test.skip -Dcompiler.version="$(RELVERSION)" -pl externs/pom.xml,pom-main.xml,pom-main-shaded.xml


DEPLOYDIR:=Closure

zip: $(DEPLOYDIR)/$(JARNAME)

$(DEPLOYDIR)/$(JARNAME): $(TARGETJAR) $(DEPLOYDIR)
	cp $< $@
	jar cMf $(DEPLOYDIR)/$(NAME).zip $@
	cp $(DEPLOYDIR)/$(NAME).zip $(DEPLOYDIR)/$(PREFIX)-latest.zip

$(DEPLOYDIR):
	mkdir $@

clean:
	$(BT) clean
	rm -rf $(DEPLOYDIR)

rebuild: clean build
