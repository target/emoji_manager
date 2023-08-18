FROM eclipse-temurin:17-jre-jammy

ADD build/distributions/emoji_manager.tar /

CMD ["/emoji_manager/bin/emoji_manager"]
