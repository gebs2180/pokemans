# Terms changelog

Each text here is one version of the terms an org's members accept. Acceptances are tied to the exact text by its
SHA-256, so a changed text needs a new file and a new version string in `identity/Consents.java`, and the old
acceptances stop counting. Keep the old file: it is what earlier members agreed to. `TermsTextsTest` pins each
version's hash. Every text is a plain-language draft for Zach, not reviewed legal wording.

- `seller-*` is what a seller commits to by posting a card. Collectors accept it, and contributors accept it after
  their own terms. The seller text is "By posting a card for sale here you agree that:", this body, and "Ask Zach if
  anything here is unclear."
- `contributor-*` is what a contributor agrees to by sharing photos and grading results, followed by the seller body.
- `buyer-firm-*` is what a buyer commits to by accepting an instant offer.

## seller

- 2026-10-21: first version.
- 2026-10-26: the $500 beta maximum is gone; offers go up to what a label can insure.
- 2026-11-01 (`seller-2026-11-01`): how the seller gets paid goes to the buyer, the seller says in the app when they
  shipped and whether they were paid, and a card returned as not genuine waits for our review.
- 2026-11-12 (`seller-2026-11-12`): after a sale each side sees the other's name but not their email; problems go to us.

## contributor

- 2026-10-21: contributors can sell too, so their terms carry the seller terms.
- 2026-11-01 (`contributor-2026-11-01`): carries the 2026-11-01 seller body.
- 2026-11-12 (`contributor-2026-11-12`): carries the 2026-11-12 seller body.

## buyer

- Firm offers: one price, no grade settled on arrival.
- 2026-10-21: adds transit loss, cancelled deals, cards subject to authentication, disputes, non-payment and dealing
  around the app.
- 2026-11-01 (`buyer-firm-2026-11-01`): arriving and paying are recorded apart, the buyer gets the seller's payment
  details, the seller says whether they were paid, and "not genuine" needs a reason.
- 2026-11-12 (`buyer-firm-2026-11-12`): after a sale each side sees the other's name but not their email; problems go to us.
