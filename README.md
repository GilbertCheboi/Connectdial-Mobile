# ConnectDial Mobile

The Android assistant keeps its native login screen and uses the original native
registration page, with email signup connected to ConnectDial. Username or email and password are sent as JSON to
`https://call.agrieldo.com/api/v1/auth/login/`. Registration sends `username`,
`email`, `password`, and `password_confirmation` to `/api/v1/auth/register/`.
Both return `access`, `token_type: Bearer`, and `expires_in: 300` when email
verification is disabled. Registration uses the website's username, email,
duplicate-account, and password validation rules.

The app calls `/api/v1/phone/` with `Authorization: Bearer <access>` to fetch the
user's assigned SIP credentials. The signed JWT is scoped to phone provisioning,
expires after five minutes, and stays in memory during setup. Password changes
and account deactivation invalidate it. There is no refresh token; retry sign-in
if setup expires. Existing Knox API endpoints remain available for legacy clients.
Website passwords are never used as SIP passwords. Password recovery opens the
website in the browser. Native API two-factor integration remains deferred.

If mandatory email verification is enabled, registration returns an account-created
message and sends a confirmation email without issuing a JWT. Sign in after
verification. New accounts without an assigned extension see an account-created
message explaining the next step, rather than a registration failure.

Deploy the Django changes in the parent repository before using this app.
No database migration is required. Phone provisioning requires an available
Hotdesk assigned to that user with a SIP secret and SIP server. The primary
available eligible extension is preferred. New sign-ups need an administrator
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

On a device, sign in with an existing account or create one on the native registration page.
Phone setup runs after sign-in; retry login after assigning an extension or
correcting a network problem. Verify registration and two-way audio on Wi-Fi and mobile data.
