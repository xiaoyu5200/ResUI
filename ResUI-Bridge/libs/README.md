# libs

ResUI-Bridge compiles against the **Residence** API, which is not redistributed here.

To build the plugin, download Residence and put its jar in this folder, for example:

```
libs/Residence6.0.0.1.jar
```

Get it from the official Residence page:
https://www.spigotmc.org/resources/residence.11480/

Any file matching `Residence*.jar` is picked up automatically, so no changes to
`build.gradle` are needed. The jar is only needed at compile time — at runtime the
classes come from the Residence plugin installed on your server.