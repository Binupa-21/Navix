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

## 🤝 Contributing
1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request
