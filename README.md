# Navix AR Indoor Navigation

Navix is an Android-based Augmented Reality (AR) indoor navigation application that allows users to map out indoor environments (like university campuses or office buildings) and navigate through them using AR markers.

## 🚀 Features
- **AR Mapping (Admin Mode):** Map physical indoor locations and save them as Nodes in a graph.
- **Cloud Anchors Integration:** Syncs local AR anchors with Google ARCore Cloud Anchors for persistent mapping.
- **Firebase Database:** Stores the node graph and building data in Firebase Firestore.
- **A* Pathfinding:** Calculates the shortest 3D distance between nodes to generate breadcrumb trails.
- **Live Navigation:** Renders a 3D breadcrumb trail for users to follow in AR.

## 📂 Project Structure
The project has recently been refactored to separate concerns into logical packages:
- `com.navix.app.data`: Contains data models (`Node`, `NodeData`) and will eventually host the Firebase `MapRepository`.
- `com.navix.app.ar`: Placeholder package for future AR-specific logic isolation (`ARManager`).
- `com.navix.app.navigation`: Contains the A* routing logic (`PathFinder`) and will host the `NavigationManager`.
- `com.navix.app.ui`: Contains the main application screens (`MainActivity`, `MenuActivity`).

## 🛠 Prerequisites & Setup
1. **Android Studio**: Ensure you are using the latest version of Android Studio.
2. **ARCore Support**: You must have a physical Android device that supports Google Play Services for AR.
3. **Firebase**: 
   - Add your `google-services.json` to the `app/` directory.
   - Enable Firestore Database in your Firebase Console.
4. **Google Cloud AR API**:
   - Ensure you have a valid API Key for the ARCore Cloud Anchor API in your `AndroidManifest.xml`.

## ⚠️ Known Issues & Technical Debt (To Add to GitHub)
During our latest architectural review, the following issues were identified and should be added to the GitHub Issues tracker for resolution:

1. **God Object Anti-Pattern in `MainActivity`**
   - **Issue:** `MainActivity.kt` currently holds over 1,100 lines of code, managing UI, Firebase logic, AR scene lifecycle, and Pathfinding math all at once.
   - **Action Required:** Fully extract Firebase calls into a `MapRepository` class, and move AR rendering logic into a dedicated `ARManager` class to prevent memory leaks and improve readability.

2. **AR Model Caching and Resource Exhaustion**
   - **Issue:** The application occasionally re-loads `.glb` files continuously inside rendering loops (e.g., drawing breadcrumbs). This leads to a `ResourceExhaustedException`.
   - **Action Required:** Ensure `ModelInstance` objects are cached firmly at the start of the application and reused. The recent restructuring laid the groundwork for this, but rigorous testing is needed.

3. **Missing Unit Tests**
   - **Issue:** `PathFinder.kt` handles complex 3D math and A* algorithm logic but has no automated test coverage.
   - **Action Required:** Add JUnit tests for the `PathFinder` logic to verify edge cases (e.g., disconnected nodes, same floor vs multi-floor penalties).

4. **Coroutines and Lifecycle**
   - **Issue:** There are multiple coroutine scopes inside `MainActivity` that could lead to memory leaks if the Activity is destroyed while a network call or model load is running.
   - **Action Required:** Ensure all asynchronous tasks correctly leverage `lifecycleScope` and check `isActive` statuses before updating UI components.

## 🤝 Contributing
1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request
