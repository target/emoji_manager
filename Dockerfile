FROM eclipse-temurin:25-jre-jammy

ADD build/distributions/emoji_manager.tar /

CMD ["/emoji_manager/bin/emoji_manager"]
