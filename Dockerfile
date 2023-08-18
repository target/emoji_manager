FROM eclipse-temurin:20-jre-jammy

ADD build/distributions/emoji_manager.tar /

CMD ["/emoji_manager/bin/emoji_manager"]
