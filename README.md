# Inventory Manager

A simple manager service which monitors a set of pv's, upon a value change it performs a set of operations including.
1. creating a log entry
2. updating channelfinder
3. email configured user

Upon startup
The inventory manager service will query channelfinder for all channels with property *serialNumber*.
It creates monitors on the above channels, on pv value change events the inventory manager will update the value of 
*serialNumber* property in channelfinder and create a log entry in Olog.


## Building ##

### Requirements
 - [JDK11 or later, suggested is OpenJDK](http://jdk.java.net/11).
 - [maven 2.x](https://maven.apache.org/) or [ant](http://ant.apache.org/)


### Build with maven

```
mvn clean install
```

### Start Inventory Manager

```
mvn exec:java
```

