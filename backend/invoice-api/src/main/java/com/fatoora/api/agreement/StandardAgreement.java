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

    static final String TITLE_AR = "اتفاقية توريد عامة";

    /** The same agreement in Arabic, clause for clause, with the same {{placeholders}}. */
    static final String BODY_AR = """
            أُبرمت اتفاقية التوريد العامة هذه ("الاتفاقية"، رقم {{agreement_number}}) بتاريخ {{date}} بين كل من:

            الطرف الأول ("الشركة"): {{company_name}}، سجل تجاري رقم {{company_cr}}، رقم التسجيل الضريبي {{company_vat}}، العنوان: {{company_address}}.

            الطرف الثاني ("العميل"): {{client_name}}
            {{client_details}}

            ويُشار إلى كل من الشركة والعميل بـ"الطرف" وإليهما معًا بـ"الطرفين".

            حيث إن الشركة تعمل في التجارة العامة وتوريد البضائع، ويرغب العميل في الحصول على بضائع وخدمات مرتبطة بها من الشركة، فقد اتفق الطرفان، وهما بكامل أهليتهما المعتبرة شرعًا ونظامًا للتعاقد، على ما يلي.

            ## 1. موضوع الاتفاقية
            تحدد هذه الاتفاقية الشروط العامة التي تورّد بموجبها الشركة البضائع والخدمات المرتبطة بها إلى العميل. ويُعد التمهيد أعلاه جزءًا لا يتجزأ من هذه الاتفاقية.

            ## 2. نطاق التوريد
            - البضائع والخدمات هي ما يرد وصفه في كل طلب كتابي أو عرض سعر يقبله الطرفان ("الطلب")، بما في ذلك وصفها وكميتها ومواصفاتها ومكان التسليم وتاريخه.
            - يُعد كل طلب جزءًا من هذه الاتفاقية. وإذا تعارض طلب مع هذه الاتفاقية، تُقدَّم أحكام هذه الاتفاقية ما لم ينص الطلب صراحةً على خلاف ذلك.

            ## 3. التزامات الشركة
            - توريد البضائع والخدمات وفق الطلب المقبول والمواصفات المتفق عليها.
            - تغليف البضائع ووضع البيانات عليها بما يضمن سلامة نقلها ومناولتها.
            - إبلاغ العميل فورًا بأي تأخير متوقع أو تغيير في التوريد.
            - الالتزام بالأنظمة واللوائح المعمول بها في المملكة العربية السعودية.

            ## 4. التزامات العميل
            - تقديم معلومات ومواصفات دقيقة وكاملة لكل طلب.
            - تسمية شخص مفوض لاستلام البضائع والتوقيع على مذكرة التسليم.
            - استلام البضائع في الزمان والمكان المتفق عليهما وفحصها عند التسليم.
            - الالتزام بالأنظمة واللوائح المعمول بها في المملكة العربية السعودية.

            ## 5. التسليم والقبول
            - يتم التسليم في المكان المتفق عليه في الطلب. ومواعيد التسليم تقديرية ما لم يُتفق كتابةً على أنها ثابتة.
            - يُبلغ العميل كتابةً عن أي نقص أو تلف ظاهر خلال ثلاثة (3) أيام عمل من التسليم، وبعد انقضاء هذه المدة يُعد التسليم مقبولًا.

            ## 6. الجودة والضمان والإرجاع
            - تضمن الشركة مطابقة البضائع للوصف المتفق عليه وخلوها من العيوب الجوهرية عند التسليم.
            - تُصلَح أو تُستبدل، بحسب اختيار الشركة، البضائع المعيبة أو الموردة خطأً متى أُبلغ عنها خلال سبعة (7) أيام من التسليم.
            - لا يجوز إرجاع البضائع الموردة بشكل صحيح إلا بموافقة كتابية من الشركة وبحالتها الأصلية غير المستعملة.

            ## 7. المدد والتأخير
            لا تتحمل الشركة مسؤولية التأخير الناتج عن:
            - تأخر المصنّعين أو الموردين أو الجمارك أو شركات النقل؛
            - الأنظمة أو المنصات أو الإجراءات الحكومية؛
            - تأخر العميل في تقديم المعلومات أو نقصها.
            ولا يُعد هذا التأخير إخلالًا بهذه الاتفاقية، وتُبلغ الشركة العميل وتُسلّم في أقرب وقت ممكن.

            ## 8. السرية
            يحافظ كل طرف على سرية جميع شروط هذه الاتفاقية وجميع المعلومات التجارية التي يتلقاها من الطرف الآخر، ولا يستخدمها إلا لأغراض هذه الاتفاقية، إلا إذا اقتضى النظام الإفصاح عنها.

            ## 9. المسؤولية
            لا يتحمل أي من الطرفين المسؤولية عن الخسائر غير المباشرة أو التبعية. ولا يحد أي حكم في هذه الاتفاقية من أي مسؤولية لا يجوز تحديدها بموجب أنظمة المملكة العربية السعودية.

            ## 10. القوة القاهرة
            لا يتحمل أي من الطرفين مسؤولية التأخير أو الإخفاق الناتج عن أحداث خارجة عن سيطرته المعقولة، بما في ذلك الكوارث الطبيعية والأوبئة والإجراءات الحكومية والحروب والتعطل الكبير في النقل. ويُبلغ الطرف المتأثر الطرف الآخر دون تأخير.

            ## 11. المدة والإنهاء
            - تبدأ هذه الاتفاقية من التاريخ المذكور أعلاه، ومدتها سنة (1) واحدة، وتتجدد تلقائيًا لمدة مماثلة ما لم يُخطر أحد الطرفين الآخر كتابةً قبل انتهاء المدة بثلاثين (30) يومًا.
            - يجوز لأي من الطرفين إنهاء هذه الاتفاقية بإشعار كتابي إذا أخل الطرف الآخر بها إخلالًا جوهريًا ولم يعالج الإخلال خلال خمسة عشر (15) يومًا من مطالبته بذلك.
            - لا يؤثر الإنهاء على الطلبات المقبولة قبله.

            ## 12. النظام الواجب التطبيق وتسوية النزاعات
            تخضع هذه الاتفاقية لأنظمة المملكة العربية السعودية. ويسعى الطرفان أولًا إلى تسوية أي نزاع وديًا خلال ثلاثين (30) يومًا، فإن تعذر ذلك يُحال النزاع إلى المحاكم المختصة في المملكة العربية السعودية.

            ## 13. الإشعارات
            تُوجَّه الإشعارات كتابةً إلى العناوين أو عناوين البريد الإلكتروني أو أرقام الجوال المذكورة في هذه الاتفاقية، ويُبلغ كل طرف الطرف الآخر كتابةً بأي تغيير في بيانات التواصل الخاصة به.

            ## 14. أحكام عامة
            - تمثل هذه الاتفاقية مع الطلبات المقبولة كامل الاتفاق بين الطرفين بشأن موضوعها.
            - لا يُعتد بأي تعديل إلا إذا كان مكتوبًا وموقعًا من الطرفين.
            - لا يجوز لأي من الطرفين التنازل عن حقوقه أو التزاماته بموجب هذه الاتفاقية دون موافقة كتابية من الطرف الآخر.
            - إذا ثبت بطلان أي حكم، تظل بقية الأحكام سارية.
            - حُررت هذه الاتفاقية من نسختين (2) أصليتين، بيد كل طرف نسخة.

            ## 15. القبول والتوقيع
            بالتوقيع أدناه، يقر الطرفان بأنهما قرآ هذه الاتفاقية وفهماها ويوافقان على جميع شروطها وأحكامها.
            """;

    private StandardAgreement() {}
}
