package beer.xiaoruru.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_settings")
public class AiSettings {
    @Id private Long id = 1L;
    @Lob @Column(nullable = false) private String document;
    protected AiSettings() {}
    public AiSettings(String document) { this.document = document; }
    public String getDocument() { return document; }
    public void setDocument(String document) { this.document = document; }
}
