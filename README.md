# ConnectDial Mobile

## M1 onboarding update

The native assistant offers personal signup, public organization membership
requests, and new organization applications. Sign-in now connects directly to the
user's assigned available Hotdesk extension, without an account-selection modal.
Organization management is available on the website at `/onboarding/`. Tenant
phone setup remains explicitly mocked. The updated backend
and its additive `connectdial` migration must be deployed before this client.

See [M1 API, setup and verification](../docs/onboarding.md). The phone JWT remains
phone-only; onboarding uses a separate five-minute token held in memory. Verified
email is required for tenant APIs. OTP users manage onboarding through the website.
No wallets, payments, live PBX changes or new working SIP credentials are included.

The notes below describe the preserved legacy phone-provisioning contract.

Organization invitations now send an email link through the backend. Recipients
sign up or log in in their browser, verify the invited email, and explicitly
accept membership without choosing an organization. They can then sign in in the
app and select that organization. The existing native invitation API remains
compatible; its response reports email delivery and retains a fallback token.

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
Website passwords are never used as SIP passwords. Forgot password now opens an
in-app email form and requests a link from `/api/v1/auth/password/reset/`. The
emailed link opens the existing secure website reset page; the new password works
on both web and mobile. Website recovery also accepts the account username.
Native API two-factor integration remains deferred.

If mandatory email verification is enabled, registration returns an account-created
message and sends a confirmation email without issuing a JWT. Sign in after
verification. New accounts without an assigned extension see an account-created
message explaining the next step, rather than a registration failure.

Deploy the Django changes in the parent repository before using this app.
Legacy phone provisioning itself requires no database migration; M1 onboarding
requires the new `connectdial` migration described above. Phone provisioning requires an available
Hotdesk assigned to that user with a SIP secret and SIP server. The primary
available eligible extension is preferred. New sign-ups need an administrator
to assign a working extension; M1 queues separate mock extensions and does not
create live PBX extensions automatically.

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
