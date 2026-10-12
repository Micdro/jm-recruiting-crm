# JM CRM on Windows

Each computer runs its own copy of the CRM with its own database, so everything opens and saves
instantly. The copies stay in step through a shared Google Drive folder: each computer leaves its
changes there as encrypted files and picks up the others'. Changes usually reach the other
computers within a minute. The computers never connect to each other and don't need to be on at
the same time. A computer that was off catches up as soon as it's back.

Set up the first computer completely before the others.

## 1. Install Google Drive on every computer

1. Download Google Drive for desktop from https://www.google.com/drive/download/ and install it.
2. Sign in with that person's Google account.

Google Drive then shows up in File Explorer, usually as `G:\My Drive`. It's free. OneDrive works
the same way if you'd rather use it.

## 2. Install JM CRM

1. Download **JM-CRM-Windows.zip** from the repository's Releases page (the `windows-latest`
   release). Share the file with the others however you like (Drive, email, a USB stick).
2. Unzip it somewhere permanent, such as `Documents\JM CRM`. Don't run it from the Downloads
   preview: the shortcuts point at wherever it is when you first open it.
3. Open **JM CRM.exe** in that folder. A small "Starting" window shows while it loads (the first
   start takes up to a minute while it creates the database).
4. Windows may say "Windows protected your PC", because the app isn't signed with a paid
   certificate. Click **More info**, then **Run anyway**. This happens once.

The CRM opens in your browser at http://localhost:4780. It also adds JM CRM to the Start menu and
the desktop, and starts with Windows from then on so sync keeps running.

## 3. The first computer: start the CRM

1. Choose **Start a new CRM**. Enter your name, email and a password (12 characters or more).
2. **Sync folder**: keep the suggested `G:\My Drive\JM CRM Sync`. The CRM creates the folder.
3. The app shows your **CRM key**. Copy it into your password manager now. You need it to add or
   replace a computer, and to set a new password if one is forgotten. It isn't saved on the
   computer and isn't shown again.
4. Sign in.
5. On the **Team** page, add an account for everyone else, with their full names (for example
   Dimitrios Drougas). Give each person their password privately.
6. Share the sync folder: at https://drive.google.com, right-click **JM CRM Sync**, choose
   **Share**, and add each person's Google account as an **Editor**.
7. Import your ClickUp lists on the **Import** page.

## 4. Every other computer: join

1. At https://drive.google.com (signed in as that person), open **Shared with me**, right-click
   **JM CRM Sync**, and choose **Organize**, then **Add shortcut**, then **My Drive**. Give it a
   minute to appear in File Explorer under `G:\My Drive`.
2. Open JM CRM and choose **Join the team's CRM**. It finds the folder; check that the path ends in
   `JM CRM Sync`.
3. Paste the CRM key. Share it through your password manager or read it out on a call, not by
   email or text.
4. The CRM copies everything from the folder. Then sign in with your own account.

The first computer doesn't need to be on for this.

## Day to day

- Open JM CRM from the Start menu or the desktop shortcut. If it's already running, that just opens
  the browser.
- **This computer** (in the sidebar) shows the sync folder, each other computer, when it last ran
  and whether you have its changes. The line under your name in the sidebar says "Synced" when
  everything that has reached the folder is in.
- Install it as an app for its own window: in Edge or Chrome, click the install icon at the right
  end of the address bar.
- Two people can work at the same time. If two people change the same record before their
  computers sync, the later change wins. Logged touches and notes are always kept.
- Leave the files in **JM CRM Sync** alone. The CRM tidies them up itself.

## Faster sync (optional)

Through the folder, changes take about a minute. To get them within seconds, turn on **Direct
connections** on **This computer** for one computer that's usually on, such as your desktop at home.
Windows asks twice about network access: click **Allow access**, then **Yes**.

- The CRM asks your router to let the other computers in (most home routers allow this; it's
  called UPnP). The page then says "Other homes connect at …".
- The other computers find it through the folder and connect to it by themselves. They don't need
  this turned on, and their changes reach it the same way.
- If the page says your router didn't open a port, or that your internet provider shares your
  address, other homes can't connect. Computers in the same house still can, and everything keeps
  syncing through the folder as before.
- Only computers with the CRM key get an answer, and everything sent is encrypted.

## Locked until you sign in

Everything that identifies a client or candidate is encrypted in the database, and the key that
reads it isn't stored on the computer. Your password unlocks it: signing in opens the CRM, and it
locks again when everyone on that computer has signed out, after 12 hours without use, or when the
app closes or the computer restarts. While it's locked, nobody can read the data, even with the
database files in hand. Sync keeps running, so changes still arrive.

The files in the sync folder are encrypted too. Google, or anyone else who gets into the Drive
account, can't read them.

- **Forgot your password?** Ask an admin to set a new one on the Team page. Or, on the sign-in
  page, click **Forgot your password?** and use the CRM key from your password manager.
- **"Unlock the CRM" after signing in**: your account can't open the CRM on this computer yet
  (for example, it was set up with an older version). Paste the CRM key once. After that, your
  password does it.

## Backups and the key

- Every computer holds a full copy, and the sync folder holds one more, so losing a computer loses
  nothing.
- The CRM key is the one thing that can't be replaced. Keep it in your password manager.
- Use a password of 12 characters or more that you don't use anywhere else. It protects the data
  on every computer.
- Each computer's data is in `%LOCALAPPDATA%\JMCRM` (the database, settings and logs). Turn on
  disk encryption on every computer, since each one carries the full client and candidate list:
  search Settings for "Device encryption" (Windows Home) or "BitLocker" (Windows Pro).

## Updating

The CRM checks for a new version every few hours. When one is ready, a strip at the top of the page
says so: click **Update now**. It downloads the new version, restarts in about a minute, and you
sign in again. Your data and settings aren't touched. **This computer** shows each computer's
version and points out any that need updating, so update the others when they offer it too.

Copies installed before October 12, 2026 can't update themselves yet. Update those once by hand:
on **This computer**, click **Close**, replace the JM CRM folder with the one from the new zip (same
location), and open JM CRM.exe. If your computers were set up with a version that synced over
Tailscale, choose the sync folder on **This computer** afterwards: on the first computer first, then
on the others once the folder is shared with them.

## If something isn't syncing

**This computer** shows the reason. The usual fixes:

- **"Can't find the sync folder"**: Google Drive isn't running or isn't signed in. Open Google
  Drive from the Start menu.
- **"Waiting for Google Drive to finish copying"**: normal for a minute or two after the other
  computer was used. If it lasts longer, click the Google Drive icon in the taskbar and check that
  it's syncing.
- **"The sync folder belongs to a CRM with a different key"**: this computer was started as a
  separate CRM, or the wrong folder was chosen. Choose the shared **JM CRM Sync** folder under
  **Sync folder**, or, if this computer was started on its own by mistake, close the CRM, delete
  `%LOCALAPPDATA%\JMCRM`, open it again and choose **Join the team's CRM**.
- The folder moved, or Google Drive uses a different drive letter now: under **Sync folder**,
  click **Change** and choose it again.

Logs are in `%LOCALAPPDATA%\JMCRM\logs`. If the app won't start, it shows a message and writes
`startup-error.txt` there.
