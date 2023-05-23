# iTunes Backup Explorer

iTunes Backup Explorer is a graphical open-source tool that can show, extract,
and replace files in iPhone and iPad backups.

It supports both **encrypted** and non-encrypted backups, 
currently from iOS 10.2 onwards.

Most programs that support encrypted backups are either limited trials 
or expensive. There are apparently only very few open-source projects 
that target this issue and none that are also useful for the average user.

![Program screenshot](https://user-images.githubusercontent.com/12913518/164055723-2d234fa8-922f-439d-974c-f9e7e560a438.png)

## Installation

- Open your terminal and type in `java -version`.
- If the command was not found or the version is below **11**, 
download and install Java for your operating system, e.g. from [here](https://www.oracle.com/java/technologies/downloads).
- Download the jar file of the [latest release](https://github.com/MaxiHuHe04/iTunes-Backup-Explorer/releases/latest) of iTunes Backup Explorer.

**Windows**
- Simply double-click the downloaded file to start the program.
- From the command line: `java -jar JARFILE.jar`.
Replace `JARFILE.jar` with the name of the file you downloaded.

**macOS**
- `cd` to the download directory and type in `chmod +x JARFILE.jar`.
- You may need to enable Full Disk Access in System Preferences ->
  Security -> Privacy for the [Jar Launcher](https://stackoverflow.com/a/66762230) or Terminal.app / iTerm.app.
- Now, you should be able to simply double-click the file to start the program.
- If that does not work, you may need to type `java -jar JARFILE.jar` into the terminal to run it.

**Linux**
- `cd` to the download directory and type in `chmod +x JARFILE.jar`.
- Depending on your specific system, you should be able to double-click the file to start the program.
- If that does not work, use `java -jar JARFILE.jar` to run it.

## Privacy

For me, this was a matter of course, but it was pointed out that I should clarify it anyway.
I do not collect any personal data. In fact, the program does not even use an internet connection at this time.
If that should change at some point in the future, I will update this notice.

## Credits

I started looking into this after I saw 
[this brilliant answer](https://stackoverflow.com/a/13793043/8868841) 
on StackOverflow by [andrewdotn](https://stackoverflow.com/users/14558/andrewdotn) 
to a question that has already been viewed more than 220.000 times. It 
explains in detail how iOS backups are structured and how they are 
encrypted, even providing a working code example.

So a huge thanks to him,

his sources
[iPhone Data Protection in Depth](https://conference.hitb.org/hitbsecconf2011ams/materials/D2T2),
iOS Hacker's Handbook, 
[a GitHub comment](https://github.com/horrorho/InflatableDonkey/issues/41#issuecomment-261927890),
the [iphone-dataprotection](https://code.google.com/archive/p/iphone-dataprotection/) project
and the Apple iOS Security Guide for iOS 11 (in the [Web Archive](http://web.archive.org/web/20180615172056/https://www.apple.com/business/docs/iOS_Security_Guide.pdf))

and [Forensic Analysis of iTunes Backups](http://www.farleyforensics.com/2019/04/14/forensic-analysis-of-itunes-backups/) by Jack Farley
