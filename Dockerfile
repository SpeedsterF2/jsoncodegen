# FROM azul/zulu-openjdk-alpine:17.0.4.1 as build
FROM azul/zulu-openjdk-alpine:17.0.19-jdk as build

# replace '--strip-debug' with '--strip-java-debug-attributes' or install package binutils
# Avoid error 'Cannot run program "objcopy": error=2, No such file or directory'!
RUN ["jlink", \
     "--compress=2", \
     "--strip-java-debug-attributes", \
     "--no-header-files", \
     "--no-man-pages", \
     "--module-path", "/usr/lib/jvm/zulu17-ca/jmods", \
     "--add-modules", "java.base,java.logging,java.desktop,java.naming,java.prefs,java.xml,java.scripting,java.sql", \
     "--output", "/custom_jre"]

#FROM alpine:latest
FROM alpine:3.23.5
COPY --from=build /custom_jre /opt/jre/

RUN ln -s /opt/jre/bin/java /usr/bin/

ADD build/release /opt/jsonCodeGen/

ENTRYPOINT ["/opt/jsonCodeGen/jsonCodeGen.sh"]

CMD ["--help"]