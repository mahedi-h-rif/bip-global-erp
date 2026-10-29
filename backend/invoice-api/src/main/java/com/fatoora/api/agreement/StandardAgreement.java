package com.fatoora.api.agreement;

/**
 * The standard supply agreement every organization starts with; the admin edits it under Settings.
 * Plain text: "## " starts a clause heading, "- " a bullet, a blank line a new paragraph.
 * {{placeholders}} are filled in on each agreement. It deliberately holds no amounts or payment terms:
 * those belong on the quotations and invoices issued under it.
 */
final class StandardAgreement {

    static final String TITLE = "General Supply Agreement";

    static final String BODY = """
            This General Supply Agreement (the "Agreement", no. {{agreement_number}}) is made on {{date}} between:

            First Party (the "Company"): {{company_name}} ({{company_name_ar}}), Commercial Registration no. {{company_cr}}, VAT registration no. {{company_vat}}, address: {{company_address}}.

            Second Party (the "Client"): {{client_name}}
            {{client_details}}

            The Company and the Client are each a "Party" and together the "Parties".

            Whereas the Company is engaged in general trading and the supply of goods, and the Client wishes to obtain goods and related services from the Company, the Parties, having full legal capacity to contract, agree as follows.

            ## 1. Objective of the Agreement
            This Agreement sets out the general terms under which the Company supplies goods and related services to the Client. The preamble above forms an integral part of this Agreement.

            ## 2. Scope of supply
            - The goods and services are those described in each written order or quotation accepted by both Parties (an "Order"), including their description, quantity, specifications and the place and date of delivery.
            - Each Order forms part of this Agreement. If an Order conflicts with this Agreement, this Agreement prevails unless the Order expressly states otherwise.

            ## 3. Obligations of the Company
            - Supply the goods and services according to the accepted Order and the agreed specifications.
            - Pack and label the goods properly for safe transport and handling.
            - Inform the Client promptly of any expected delay or change in supply.
            - Comply with the laws and regulations of the Kingdom of Saudi Arabia.

            ## 4. Obligations of the Client
            - Provide accurate and complete information and specifications for each Order.
            - Name a person authorised to receive the goods and sign the delivery note.
            - Receive the goods at the agreed time and place and inspect them on delivery.
            - Comply with the laws and regulations of the Kingdom of Saudi Arabia.

            ## 5. Delivery and acceptance
            - Delivery takes place at the place agreed in the Order. Delivery dates are estimates unless agreed in writing as fixed.
            - The Client reports any shortage or visible damage in writing within three (3) working days of delivery. After that period, the delivery is deemed accepted.

            ## 6. Quality, warranty and returns
            - The Company warrants that the goods match the agreed description and are free from material defects on delivery.
            - Defective or wrongly supplied goods reported within seven (7) days of delivery are repaired or replaced, at the Company's choice.
            - Correctly supplied goods may be returned only with the Company's written approval and in their original, unused condition.

            ## 7. Timeline and delays
            The Company is not responsible for delays caused by:
            - delays of manufacturers, suppliers, customs or transport companies;
            - government systems, portals or procedures;
            - late or incomplete information from the Client.
            Such delays are not a breach of this Agreement. The Company informs the Client and delivers as soon as reasonably possible.

            ## 8. Confidentiality
            Each Party keeps confidential all terms of this Agreement and all business information received from the other Party, and uses it only for this Agreement, except where disclosure is required by law.

            ## 9. Liability
            Neither Party is liable for indirect or consequential loss. Nothing in this Agreement limits any liability that cannot be limited under the laws of the Kingdom of Saudi Arabia.

            ## 10. Force majeure
            Neither Party is responsible for a delay or failure caused by events beyond its reasonable control, including natural disasters, epidemics, government action, war or major disruption of transport. The affected Party informs the other Party without delay.

            ## 11. Term and termination
            - This Agreement starts on the date above, runs for one (1) year and renews automatically for the same period unless either Party gives thirty (30) days' written notice before the end of the term.
            - Either Party may terminate this Agreement by written notice if the other Party materially breaches it and does not remedy the breach within fifteen (15) days of being asked to.
            - Termination does not affect Orders already accepted before it.

            ## 12. Governing law and disputes
            This Agreement is governed by the laws of the Kingdom of Saudi Arabia. The Parties first try to settle any dispute amicably within thirty (30) days. If they cannot, the dispute is referred to the competent courts of the Kingdom of Saudi Arabia.

            ## 13. Notices
            Notices are given in writing to the addresses, email addresses or mobile numbers stated in this Agreement. Each Party informs the other in writing of any change to its contact details.

            ## 14. General provisions
            - This Agreement, together with the accepted Orders, is the entire agreement between the Parties about its subject.
            - Changes are valid only in writing signed by both Parties.
            - Neither Party may transfer its rights or obligations under this Agreement without the written consent of the other Party.
            - If any provision is found invalid, the remaining provisions stay in force.
            - This Agreement is made in two (2) original copies, one for each Party.

            ## 15. Acceptance and signatures
            By signing below, both Parties confirm that they have read and understood this Agreement and agree to all its terms and conditions.
            """;

    private StandardAgreement() {}
}
