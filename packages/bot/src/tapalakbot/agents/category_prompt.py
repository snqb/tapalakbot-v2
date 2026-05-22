"""Generate compact category prompt from live category tree.

Instead of dumping the full tree (26K), generates a focused map with
Russian query hints. ~3K chars for the LLM.
"""

from __future__ import annotations

from lalafo_client.categories import CategoryTree

IMPORTANT_ROOTS = {
    "Electronics", "Transport", "Property",
    "Sport & hobby", "Services", "Job", "Pets",
}

# Russian hints so the LLM maps "роутер" → Networking, not generic Electronics
RUSSIAN_HINTS: dict[str, str] = {
    "Headphones": "наушники, блютуз наушники, TWS",
    "Modems, Broadband & Networking": "роутер, маршрутизатор, wifi, модем",
    "Laptops and Netbooks": "ноутбук, лэптоп",
    "Mobile Phones": "телефон, смартфон, iPhone, Samsung",
    "TVs": "телевизор, ТВ",
    "Speakers & sound systems": "колонка, акустика",
    "Washing machine": "стиральная машина",
    "Refrigerators": "холодильник",
    "Apartments for rent": "аренда квартиры, снять квартиру",
    "Sale of apartments": "купить квартиру",
    "Houses for sale": "дом продажа, купить дом",
    "Used cars": "автомобиль, машина",
    "Bicycles": "велосипед, велик",
    "Computer Parts": "видеокарта, процессор, оперативка",
    "Video Games & Consoles": "приставка, PS5, PlayStation, Xbox",
    "Photo Cameras": "фотоаппарат",
    "Smart watches": "смарт часы, apple watch",
    "Vacancy": "вакансия, работа",
    "Resumes, CVs": "резюме",
    "Musicial instruments": "гитара, пианино, музыкальные инструменты",
    "Hunting and fishing": "рыбалка, охота, удочка",
}


def build_category_prompt(tree: CategoryTree) -> str:
    """Build compact category prompt from live tree. ~3K chars."""
    lines = []
    
    for root in tree.roots:
        if root.name not in IMPORTANT_ROOTS:
            continue
        
        lines.append(f"\n{root.name} [{root.id}]:")
        child_shown = 0
        for child in root.children:
            if child.name.startswith(("AZ -", "KG -", "RS -")):
                continue
            child_shown += 1
            if child_shown > 10:
                break
            
            hint = RUSSIAN_HINTS.get(child.name, "")
            hint_str = f" ← {hint}" if hint else ""
            
            # For categories with children, show only the hinted/important ones
            if child.is_leaf:
                lines.append(f"  [{child.id}] {child.name}{hint_str}")
            else:
                lines.append(f"  [{child.id}] {child.name}{hint_str}")
                # Show only children that have hints (= commonly searched)
                for gc in child.children:
                    if gc.name.startswith(("AZ -", "KG -", "RS -")):
                        continue
                    gc_hint = RUSSIAN_HINTS.get(gc.name, "")
                    if gc_hint:
                        lines.append(f"    [{gc.id}] {gc.name} ← {gc_hint}")
    
    lines.append("\nRULES:")
    lines.append("- Pick the MOST SPECIFIC (deepest) category_id.")
    lines.append("- For 'наушники' → use Headphones leaf, NOT Audio or Electronics.")
    lines.append("- For 'роутер' → use Networking leaf, NOT Computers or Electronics.")
    lines.append("- If unsure about category, set category_id: null and use keyword search.")
    
    return "\n".join(lines)
