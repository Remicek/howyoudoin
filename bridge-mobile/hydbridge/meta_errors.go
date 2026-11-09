package hydbridge

import (
	"errors"
	"fmt"

	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
)

// friendlyMetaError says what to do when Messenger or Instagram won't load the inbox until the account owner acts on
// their website (site is e.g. "messenger.com"); other errors keep their detail. The app shows it in the inbox banner,
// whose Reconnect opens the page (see metaActionURL).
func friendlyMetaError(err error, site string) error {
	switch {
	case errors.Is(err, httpclient.ErrConsentRequired):
		return fmt.Errorf("Facebook wants you to confirm your cookie choices on %s first. Tap Reconnect to open "+
			"the page, make your choice there and tap Done", site)
	case errors.Is(err, httpclient.ErrChallengeRequired), errors.Is(err, httpclient.ErrCheckpointRequired):
		return fmt.Errorf("Facebook wants to check it's you on %s first. Tap Reconnect to open the page, follow "+
			"the steps there and tap Done", site)
	}
	return fmt.Errorf("failed to load inbox: %w", err)
}

// metaActionURL is the page where the account owner has to act before Messenger or Instagram works again (a
// consent or checkpoint page), or "" for other errors.
func metaActionURL(err error) string {
	if !errors.Is(err, httpclient.ErrConsentRequired) && !errors.Is(err, httpclient.ErrChallengeRequired) &&
		!errors.Is(err, httpclient.ErrCheckpointRequired) {
		return ""
	}
	return httpclient.GetErrorRedirectURL(err)
}
