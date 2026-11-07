# MediTrack ML Dataset Specification & Stack Architecture

## 1. Overview & Stack Strategy
No single public dataset provides per-cell full/empty labels on real used strips photographed with mobile phones. MediTrack uses a three-tier dataset stack:

| Dataset | Source | Primary Role | Cell-Level Labels |
|---|---|---|---|
| **1. Blister Pills Segmentation** | Roboflow Universe (v7) | Label source: teaches full vs empty cell features | Yes (`Empty`, `Full`, `Blister`) |
| **2. Pharmassist Dataset** | Mendeley Data (CC BY 4.0) | Real-world realism: Indian blister packs, varied lighting and angles | No (Single tablet class per image) |
| **3. Own Phone Photos** | Stage 5 Data-Collection Mode | Gold held-out test set & `cell_partial` ground truth | Yes (`full`, `empty`, `partial`, `strip`) |

---

## 2. Dataset 1: Blister Pills Segmentation (v7)
- **Source**: Roboflow Universe (`blisterdetection/blister-pills-segmentation/7`)
- **Size**: 1,349 images
- **Classes**: `Empty`, `Full`, `Blister`
- **Annotation Type**: Instance segmentation polygons (converted to bounding boxes via `prepare_dataset.py`)
- **Meaning of `Blister`**: Inspection of 30 random samples confirms `Blister` designates the bounding perimeter of the overall blister strip. Where absent, the strip bounding box is calculated from the envelope union of all constituent cell boxes.

---

## 3. Dataset 2: Pharmassist (Used Strips)
- **Source**: Mendeley Data (DOI: `10.17632/zbnhmbbymd.1`)
- **Licence**: CC BY 4.0
- **Authors**: Kapilesh N, Tharaka P, Eesha C, Rekha D, Bhargavi R (Vellore Institute of Technology)
- **Size**: 4,997 images (**2,050 originals + 2,947 augmented copies**)
- **Zero-Leakage Partitioning Rule**:
  - The 2,947 augmented copies are derived from the 2,050 originals.
  - Grouped by `group_id` in `ml/data/pharmassist/manifest.csv`.
  - Originals and their augmented counterparts are strictly held in the same split.
  - Used exclusively for domain realism and silver pseudo-label generation ($\ge 0.8$ confidence); silver labels never enter the final test set.

---

## 4. Dataset 3: Own Phone Photos (Gold Test Set)
- Collected via the Stage 5 debug data collection mode.
- Covers varied lighting (daylight, tungsten, shadows), backgrounds, angles $\le 20^\circ$, and hard negatives.
- **Sole Ground Truth Source for `cell_partial`**: Partially crushed foil cavities and cracked tablets.
- **Split Strategy**: Partitioned strictly by **strip/session ID** (never by photo) to prevent data leakage.
