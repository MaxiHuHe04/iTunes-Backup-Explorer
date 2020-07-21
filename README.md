# iTunes Backup Explorer

iTunes Backup Explorer is a graphical open-source tool that can show 
files in iPhone and iPad backups and extract them.

It supports both **encrypted** and non-encrypted backups, 
currently from iOS 10.2 onwards.

Most programs that support encrypted backups are either limited trials 
or expensive. There are apparently only very few open-source projects 
that target this issue and none that are also useful for the average user.

### Credits

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
and the Apple iOS Secutity Guide for iOS 11 (in the [Web Archive](http://web.archive.org/web/20180615172056/https://www.apple.com/business/docs/iOS_Security_Guide.pdf))

and [Forensic Analysis of iTunes Backups](http://www.farleyforensics.com/2019/04/14/forensic-analysis-of-itunes-backups/) by Jack Farley
