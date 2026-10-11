# JM CRM on Windows

Each computer runs its own copy of the CRM with its own database, so everything opens and saves
instantly. The copies stay in step with each other over Tailscale, a private network between your
computers. Changes reach the other computers within a few seconds while they're on. A computer
that was off catches up as soon as it's back.

Set up the first computer completely before the others.

## 1. Install Tailscale on every computer

1. Download Tailscale for Windows from https://tailscale.com/download/windows and install it.
2. Sign in. Every computer must be on the same Tailscale network: either sign in with the same
   account, or invite the others from the Tailscale admin console.
3. Note each computer's Tailscale name. Click the Tailscale icon in the taskbar, or see the
   Machines page in the admin console. For example: `michael-pc`.

Tailscale only connects your own devices to each other. The CRM is never exposed to the internet.

## 2. Install JM CRM

1. Download **JM-CRM-Windows.zip** from the repository's Releases page (the `windows-latest`
   release). Share the file with the others however you like (Drive, OneDrive, a USB stick).
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
2. Windows asks twice about network access, so your computers can sync. Click **Allow access**,
   then **Yes**.
3. The app shows your **CRM key**. Copy it into your password manager now. You need it to add or
   replace a computer, and to set a new password if one is forgotten. It isn't saved on the
   computer and isn't shown again.
4. Sign in.
5. On the **Team** page, add an account for everyone else, with their full names (for example
   Dimitrios Drougas). Give each person their password privately.
6. Import your ClickUp lists on the **Import** page.

## 4. Every other computer: join

1. Make sure the first computer is on, with the CRM open (it starts with Windows) and Tailscale
   connected.
2. Choose **Join the team's CRM**.
3. Enter the first computer's Tailscale name (for example `michael-pc`) and paste the CRM key.
4. Windows asks twice about network access. Click **Allow access**, then **Yes**.
5. The CRM copies everything over. This takes a few seconds, or longer the first time on a large
   list. Then sign in with your own account.

A computer can join through any computer that's already set up, not only the first one.

## Day to day

- Open JM CRM from the Start menu or the desktop shortcut. If it's already running, that just opens
  the browser.
- **This computer** (in the sidebar) shows each other computer and when it last synced. The line
  under your name in the sidebar says "Synced" when everything is in step.
- Install it as an app for its own window: in Edge or Chrome, click the install icon at the right
  end of the address bar.
- Two people can work at the same time. If two people change the same record before their
  computers sync, the later change wins. Logged touches and notes are always kept.

## Locked until you sign in

Everything that identifies a client or candidate is encrypted in the database, and the key that
reads it isn't stored on the computer. Your password unlocks it: signing in opens the CRM, and it
locks again when everyone on that computer has signed out, after 12 hours without use, or when the
app closes or the computer restarts. While it's locked, nobody can read the data, even with the
database files in hand. Sync keeps running, so changes still arrive.

- **Forgot your password?** Ask an admin to set a new one on the Team page. Or, on the sign-in
  page, click **Forgot your password?** and use the CRM key from your password manager.
- **"Unlock the CRM" after signing in**: your account can't open the CRM on this computer yet
  (for example, it was set up with an older version). Paste the CRM key once. After that, your
  password does it.

## Backups and the key

- Every computer holds a full copy, so losing one computer loses nothing as long as another has
  synced.
- The CRM key is the one thing that can't be replaced. Keep it in your password manager.
- Use a password of 12 characters or more that you don't use anywhere else. It protects the data
  on every computer.
- Each computer's data is in `%LOCALAPPDATA%\JMCRM` (the database, settings and logs). Turn on
  disk encryption on every computer, since each one carries the full client and candidate list:
  search Settings for "Device encryption" (Windows Home) or "BitLocker" (Windows Pro).

## Updating

1. On each computer, open **This computer** and click **Close**.
2. Replace the JM CRM folder with the one from the new zip (same location).
3. Open JM CRM.exe and sign in.

Update all computers on the same day. Computers set up before the CRM locked itself kept the key
in the settings file; after the update, the first sign-in on each computer moves it into your
account and removes it from the file.

## If something isn't syncing

**This computer** shows the reason next to each computer. The usual fixes:

- **"Can't reach …"**: the other computer is off or asleep, the CRM isn't running there, or
  Tailscale is disconnected on one side. Changes wait and go through once both are back.
- **Windows Firewall** on the This computer page says other computers can't reach this one: click
  **Allow**, then **Yes**. If you clicked Cancel when Windows first asked about JM CRM, open
  Windows Security > Firewall & network protection > Allow an app through firewall, and allow
  JM CRM.
- **"uses a different CRM key"**: that computer was started as a separate CRM. On it, close the
  CRM, delete `%LOCALAPPDATA%\JMCRM`, open it again and choose **Join the team's CRM**.

Logs are in `%LOCALAPPDATA%\JMCRM\logs`. If the app won't start, it shows a message and writes
`startup-error.txt` there.
