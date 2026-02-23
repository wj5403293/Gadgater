package top.wsdx233.gadgeter

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import top.wsdx233.gadgeter.ui.HomeScreen
import top.wsdx233.gadgeter.ui.ProcessingScreen
import top.wsdx233.gadgeter.ui.ResultScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onStartProcessing = { apkPath ->
                    navController.navigate("processing?apkPath=${apkPath}")
                }
            )
        }
        composable("processing?apkPath={apkPath}") { backStackEntry ->
            val apkPath = backStackEntry.arguments?.getString("apkPath") ?: ""
            ProcessingScreen(
                apkPath = apkPath,
                onComplete = { resultApkPath ->
                    navController.navigate("result?apkPath=${resultApkPath}") {
                        popUpTo("home") { inclusive = false }
                    }
                },
                onError = { errorMsg ->
                    // Optionally handle error route or back to home
                    navController.popBackStack()
                }
            )
        }
        composable("result?apkPath={apkPath}") { backStackEntry ->
            val apkPath = backStackEntry.arguments?.getString("apkPath") ?: ""
            ResultScreen(
                apkPath = apkPath,
                onBackHome = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            )
        }
    }
}
