# ConnectDial Mobile

The Android assistant signs in and creates accounts on **https://call.agrieldo.com**.
It uses the existing website pages, including email confirmation, password resets,
and two-factor authentication. Website passwords are never used as SIP passwords.

After sign-in, `/mobile/ready/` returns the user to the app's phone setup. The app
reads `/mobile/provision/` with the authenticated website session and registers the
user's assigned extension. The website session is cleared after SIP registration
succeeds. No JavaScript bridge exposes credentials to web pages, and navigation is
restricted to HTTPS on `call.agrieldo.com`.

Deploy the Django changes in the parent ConnectDial repository before using this
version of the app. No database migration is required. Provisioning requires a
verified website account, completion of configured two-factor authentication,
and an available Hotdesk assigned to that user with a SIP secret and SIP server.
The primary available extension is preferred. New sign-ups need an administrator
to assign an extension; they do not create PBX extensions automatically.

Native SIP defaults to domain `call.agrieldo.com` and TLS proxy/outbound route
`call.agrieldo.com:5066`. These are separate from the internal registrar used by
the web dialer. Django settings `MOBILE_SIP_DOMAIN` and `MOBILE_SIP_PROXY` can
override them. An assigned Hotdesk TURN server is included for ICE/TURN audio.

From the parent repository, verify backend authentication with:

```sh
docker compose run --rm --no-deps -v "$PWD:/app" app \
  python manage.py test tests.test_mobile_authentication tests.test_authentication \
  --settings=myhelpline.auth_test_settings --noinput
```

Build the Android project with its pinned JDK/Gradle/SDK dependencies:

```sh
cd linphone-android
./gradlew :app:assembleDebug
```

On a device, create/verify a website account or sign in with an existing one.
Complete two-factor authentication if enabled. Phone setup runs after sign-in;
use **Connect phone** to retry after assigning an extension or correcting a
network problem. Verify registration and two-way audio on Wi-Fi and mobile data.
