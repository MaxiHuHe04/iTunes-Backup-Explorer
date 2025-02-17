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

<details>
<summary>Jar file with dependencies (prior to v1.7)</summary>

- Open your terminal and type in `java -version`.
- If the command was not found or the version is below **18**,
  download and install Java for your operating system, e.g. from [here](https://www.azul.com/downloads/?package=jdk-fx#zulu).
- Download the jar file of the [latest release](https://github.com/MaxiHuHe04/iTunes-Backup-Explorer/releases/latest) of iTunes Backup Explorer.

**Windows**
- Simply double-click the downloaded file to start the program.
- From the command line: `java -jar JARFILE.jar`.
  Replace `JARFILE.jar` with the name of the file you downloaded.

**macOS**
- `cd` to the download directory and type in `chmod +x JARFILE.jar`.
- Now, you should be able to simply double-click the file to start the program.
- If that does not work, you may need to type `java -jar JARFILE.jar` into the terminal to run it.

**If you have permission issues**

When exporting data from the backup files, you might get `Operation not permitted`
errors on your MacBook. To fix this, go to `System Settings > Privacy & Security > Full Disk Access` and add both the `java` binary file and the `jar` file you downloaded.

More detailed information can be checked [here](https://stackoverflow.com/questions/65469536/why-does-a-jar-file-have-no-permissions-to-read-from-disk-when-started-via-doubl/66762230#66762230).

**Linux**
- `cd` to the download directory and type in `chmod +x JARFILE.jar`.
- Depending on your specific system, you should be able to double-click the file to start the program.
- If that does not work, use `java -jar JARFILE.jar` to run it.

</details>

Starting with version 1.7, the recommended way to install the program
is using the installer for your operating system from the [latest release](https://github.com/MaxiHuHe04/iTunes-Backup-Explorer/releases/latest).

### Windows
Download and run the `_win_x64.msi` installer.
By default, it installs the program just for your user
and doesn't require administrator privileges.
Then, simply double-click the shortcut on your desktop
or use Windows search to launch it.

### macOS
Files ending with `_mac_arm64.dmg` can be used to install the program on Apple silicon processors (M1, M2, ...)
while `_mac_x64.dmg` images support Intel-based Macs.

You might have to go to `System Settings > Privacy & Security > Full Disk Access` and give the application 
full disk access to prevent `Operation not permitted` errors when trying to export files.

### Debian/Ubuntu
Download the `_debian_x64.deb` file and install it using `sudo apt install ./path/to/package.deb`.
The application is installed to `/opt/itunes-backup-explorer` and added to the desktop menu as an *archiving utility*.

## File Search
In the "File Search" tab, you can search for files using case-insensitive SQLite LIKE syntax.
It supports two wildcards: `%` and `_`.
- `%` matches any sequence of zero or more characters.
- `_` matches any single character.
- `\ ` is used as the escape character.

Here are a few examples:<br>

|                                    | Domain           | Relative Path                     |
|------------------------------------|------------------|-----------------------------------|
| Videos in the camera roll          | CameraRollDomain | %.mov                             |
| Files under the DCIM directory     | CameraRollDomain | Media/DCIM/%                      |
| All .sqlite files                  | %                | %.sqlite                          |
| .db databases in the home domain   | HomeDomain       | %.db                              |
| All WhatsApp files                 | %whatsapp%       | %                                 |
| App documents on iCloud            | HomeDomain       | Library/Mobile Documents/iCloud~% |
| All files (can take a bit of time) | %                | %                                 |

After you clicked on the `Search` button, you can also sort by clicking on a column name.

To find the largest files, type in a query, click on `Search` and then twice on `Size`.

With the `Export matching` button on the bottom right, you can export all files that match your query to a directory you choose.

By right-clicking on a file row, you can open, extract, replace or delete a single file.
This works the same as in the hierarchical "Files" tab.
If it is a symbolic link, you can show the target location.


## Privacy

For me, this was a matter of course, but it was pointed out that I should clarify it anyway.
I do not collect any personal data. In fact, the program does not even use an internet connection at this time.
If that should change at some point in the future, I will update this notice.


## How to build
### Native executables
1. Install a current JDK and Maven
2. Run `mvn clean package`
3. You can find the built executable in `target/app-image` and an installer in `target/installer`

### Jar with dependencies
To get a jar file with dependencies for your system, use `mvn clean compile assembly:single`.

You can also build a fairly cross-platform jar file with dependencies for Windows, Linux and ARM macOS using the *most_platforms* profile: `mvn clean compile assembly:single -Pmost_platforms`


## How to run directly using Maven
Use `mvn exec:exec`

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
