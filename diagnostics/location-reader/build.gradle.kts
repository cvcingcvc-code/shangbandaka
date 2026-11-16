buildscript { repositories { google(); mavenCentral(); maven("https://maven.aliyun.com/repository/google"); maven("https://maven.aliyun.com/repository/central") }; dependencies { classpath("com.android.tools.build:gradle:8.13.0") } }
allprojects { repositories { google(); mavenCentral() } }
