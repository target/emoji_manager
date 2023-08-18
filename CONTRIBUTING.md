# Contributing to Emoji Manager

## Contributing Back

`@Emoji Manager` is developed in Kotlin, and we expect `ktlint` to pass to maintain consistent coding styles. 

We would rather not have PRs without a corresponding issue describing the bug, feature, change, etc first.

## Docker

Running Emoji Manager requires a running database.  One way to do this is with the encluded `docker-compose` configuration.

Running the Gradle tests expects the `docker-compose` setup to be running.

## Local Development

Developing for Slack applications incurs some complexity from regular apps, as it requires Slack be able to reach your running instance, where ever it is. Generally, developers create a test environment that includes a dedicated Slack Grid instance (and corresponding workspace). A persistent deployment of the app is suggested, even if you are doing local development. Keep the persistent deployment accessible by Slack to handle the image serving for the `admin.emoji.add` endpoint. Even if the deployed version does not match your local development, you likely can still test various other aspects of the app using Socket Mode. This way the Slack events are routed to your local development and not the deployed app, but the deployed app will serve images.  The only thing to keep in mind is you will have 2 different systems that attempt to do the tally counting.

Developing locally from within IntelliJ is fairly easy and how the maintainers do development. It's recommended to set up an App Token (in https://api.slack.com/apps) and use Socket Mode for development. To convince the app to run with Socket Mode, you need to expose an extra environment variable named `SLACK_APP_TOKEN` with the value of that token.


## Config

It is recommended for development to tune the voting rules to move faster, and add a description to help to indicate it's a development version.

Create a `local.yaml` with your local settings. Here is an example:

```yaml
server:
  port: 3000
  urlPrefix: "https://some-host.eample.com"
  urlPath: "/slack/events"
database:
  url: "jdbc:postgresql://my_database:5432/emojimanagerdev"
  username: "emojimanagerdev"
  password: "nonegivenhere"
  adminUsername: "emojimanagerdev"
  adminPassword: "nonegivenhere"
slack:
  slackSigningSecret: "0123456"
  slackAdminToken: "xoxp-XXXXX-YYYYY"
  theSlackAppToken: "xapp-1-A1234-ZZZZZ-BBBBBB"
  slackBotToken: "xoxb-XXXXXX-YYYYY"
  slackEmojiChannel: "C123456"
  slackAdminUsers: ["W123456","W23456789"]
  slackProposalChannels: ["C123456","C234567"]
  slackHintChannels: ["C123456"]
votes:
  commentPeriod: "1"
  maxDuration: "3"
  winBy: "2"
  tallySchedule: "5"
text:
  intro: |
    *_NOTE: THIS IS A DEVELOPMENT VERSION RUNNING LOCALLY, NOT DEPLOYED_*
    Custom emojis are a community-supported effort in partnership with the Slack admins.
    Any team member in Slack can suggest a new emoji by posting an image file
    _with the suggested emoji name as the file name_ in the emoji management channel <#${slack.slackEmojiChannel}>.
    If it receives ${votes.winBy} more upvotes than downvotes before ${votes.maxDuration} business days, it will be added.
    There is a comment period of ${votes.commentPeriod} business days to ensure everyone has a chance to see the proposal.

    You may withdraw your proposal by reacting to the message with :${text.withdraw}: Note that only the original author can do this, and it cannot be undone.

    Proposals for aliases and emoji removals can be done with shortcuts in the emoji management channel.
```

You can place the `local.yaml` in the `resources` directory or in the root of the project.  Just make sure you don't accidentally commit it.
