package com.fatoora.api.document;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** The uploaded scan of a document. */
@Entity
@Table(name = "document_file")
@Getter
@Setter
public class DocumentFile {

    @Id
    private Long documentId;

    private byte[] data;
}
