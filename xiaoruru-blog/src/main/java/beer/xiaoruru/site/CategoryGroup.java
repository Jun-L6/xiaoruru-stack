package beer.xiaoruru.site;

import beer.xiaoruru.taxonomy.Category;
import java.util.List;

public record CategoryGroup(Category root, List<Category> children) {}
