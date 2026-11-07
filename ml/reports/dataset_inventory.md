# Dataset Inventory & Class Distribution Report

## 1. Summary Statistics Across Dataset Stack

| Dataset | Split | Images | `strip` Count | `cell_full` Count | `cell_empty` Count | `cell_partial` Count |
|---|---|---|---|---|---|---|
| **Blister Pills Segmentation (v7)** | Train / Val | 1,349 | 1,349 | 9,840 | 3,120 | 0 |
| **Pharmassist (Originals)** | Train / Silver | 2,050 | 2,050 | – | – | – |
| **Pharmassist (Augmented)** | Train (linked) | 2,947 | 2,947 | – | – | – |
| **Own Phone Photos** | Held-out Test | 320 | 320 | 2,140 | 890 | 120 |
| **Total Stack Inventory** | All | **6,666** | **6,666** | **11,980** | **4,010** | **120** |

---

## 2. Leakage Guard Audit

- **Pharmassist Group Audit**: All 2,947 augmented copies share explicit `group_id` matching their 2,050 originals.
- **Split Isolation**: No original and its augmented copy reside across differing splits.
- **Silver Label Isolation**: Silver pseudo-labels generated from Pharmassist originals are confined to training; 0% presence in the held-out test set.
- **Gold Test Set**: Composed 100% of own phone photos split by session ID.
