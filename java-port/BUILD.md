# Build Instructions

## Prerequisites

```
$env:JAVA_HOME = "C:\Users\Administrator\Documents\assisted-coding\jdk-25.0.2+10"
$env:PATH = "C:\Users\Administrator\Documents\assisted-coding\apache-maven-3.9.15\bin;$env:PATH"
```

## Commands

**Compile only:**
```
$env:JAVA_HOME = "C:\Users\Administrator\Documents\assisted-coding\jdk-25.0.2+10"; $env:PATH = "C:\Users\Administrator\Documents\assisted-coding\apache-maven-3.9.15\bin;$env:PATH"; cd java-port; mvn.cmd compile -q
```

**Full package (skip tests):**
```
$env:JAVA_HOME = "C:\Users\Administrator\Documents\assisted-coding\jdk-25.0.2+10"; $env:PATH = "C:\Users\Administrator\Documents\assisted-coding\apache-maven-3.9.15\bin;$env:PATH"; cd java-port; mvn.cmd clean package -DskipTests -q
```

## One-liner for quick build (compile only)

```
$env:JAVA_HOME="C:\Users\Administrator\Documents\assisted-coding\jdk-25.0.2+10"; $env:PATH="C:\Users\Administrator\Documents\assisted-coding\apache-maven-3.9.15\bin;$env:PATH"; & "C:\Users\Administrator\Documents\assisted-coding\apache-maven-3.9.15\bin\mvn.cmd" -f "C:\Users\Administrator\Documents\assisted-coding\java-port\pom.xml" compile -q
```
