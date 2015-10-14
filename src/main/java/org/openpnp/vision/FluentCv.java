package org.openpnp.vision;


import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import javax.imageio.ImageIO;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.Features2d;
import org.opencv.imgproc.Imgproc;
import org.openpnp.model.Length;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.HslColor;
import org.openpnp.util.VisionUtils;

/**
 * A fluent API for some of the most commonly used OpenCV primitives.
 * Successive operations modify a running Mat. By specifying a tag on
 * an operation the result of the operation will be stored and can be
 * recalled back into the current Mat.
 * 
 * Heavily influenced by FireSight by Karl Lew
 * https://github.com/firepick1/FireSight
 *  
 * TODO: Rethink operations that return or process data points versus
 * images. Perhaps these should require a tag to work with and
 * leave the image unchanged.
 */
public class FluentCv {
    static {
        nu.pattern.OpenCV.loadShared();
        System.loadLibrary(org.opencv.core.Core.NATIVE_LIBRARY_NAME);
    }

    private LinkedHashMap<String, Mat> stored = new LinkedHashMap<>();
	private Mat mat = new Mat();
	private Camera camera;
	
	public FluentCv toMat(BufferedImage img, String... tag) {
        Integer type = null;
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            type = CvType.CV_8UC1;
        }
        else if (img.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            type = CvType.CV_8UC3;
        }
        else {
            img = convertBufferedImage(img, BufferedImage.TYPE_3BYTE_BGR);
            type = CvType.CV_8UC3;
        }
        Mat mat = new Mat(img.getHeight(), img.getWidth(), type);
        mat.put(0, 0, ((DataBufferByte) img.getRaster().getDataBuffer()).getData());
		return store(mat, tag);
	}
	
	public FluentCv toGray(String...tag) {
    	if (mat.channels() == 1) {
    		return this;
    	}
		Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);
		return store(mat, tag);
	}
	
	public FluentCv cvtColor(int code, String... tag) {
		Imgproc.cvtColor(mat, mat, code);
		return store(mat, tag);
	}
	
	public FluentCv threshold(double threshold, boolean invert, String... tag) {
    	Imgproc.threshold(
    			mat, 
    			mat,
    			threshold,
    			255, 
    			invert ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY);
		return store(mat, tag);
	}
	
	public FluentCv thresholdOtsu(boolean invert, String... tag) {
    	Imgproc.threshold(
    			mat, 
    			mat,
    			0,
    			255, 
    			(invert ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY) | Imgproc.THRESH_OTSU);
		return store(mat, tag);
	}
	
	public FluentCv thresholdAdaptive(boolean invert, String...tag) {
    	Imgproc.adaptiveThreshold(
    			mat, 
    			mat, 
    			255, 
    			Imgproc.ADAPTIVE_THRESH_MEAN_C, 
    			invert ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY, 
    			3,
    			5);
		return store(mat, tag);
	}
	
	public FluentCv gaussianBlur(int kernelSize, String... tag) {
    	Imgproc.GaussianBlur(mat, mat, new Size(kernelSize, kernelSize), 0);
		return store(mat, tag);
	}
	
	public FluentCv houghCircles( 
    		Length minDiameter, 
    		Length maxDiameter, 
    		Length minDistance,
    		String... tag) {
		checkCamera();
        return houghCircles(
        		(int) VisionUtils.toPixels(minDiameter, camera), 
        		(int) VisionUtils.toPixels(maxDiameter, camera), 
        		(int) VisionUtils.toPixels(minDistance, camera),
        		tag);
	}
	
	public FluentCv houghCircles(int minDiameter, int maxDiameter, int minDistance, String... tag) {
    	Mat circles = new Mat();
    	Imgproc.HoughCircles(
    			mat, 
    			circles, 
    			Imgproc.CV_HOUGH_GRADIENT, 
    			1, 
    			minDistance,
    			80, 
    			10, 
    			minDiameter / 2, 
    			maxDiameter / 2);
		store(circles, tag);
		return this;
	}
	
	public FluentCv circlesToPoints(List<Point> points) {
    	for (int i = 0; i < mat.cols(); i++) {
    		double[] circle = mat.get(0, i);
    		double x = circle[0];
    		double y = circle[1];
    		double radius = circle[2];
    		points.add(new Point(x, y));
    	}
    	return this;
	}
	
	public FluentCv circlesToLocations(List<Location> locations) {
		checkCamera();
    	Location unitsPerPixel = camera
    			.getUnitsPerPixel()
    			.convertToUnits(camera.getLocation().getUnits());
    	double avgUnitsPerPixel = (unitsPerPixel.getX() + unitsPerPixel.getY()) / 2;

    	for (int i = 0; i < mat.cols(); i++) {
    		double[] circle = mat.get(0, i);
    		double x = circle[0];
    		double y = circle[1];
    		double radius = circle[2];
            Location location = VisionUtils.getPixelLocation(camera, x, y);
            location = location.derive(null, null, null, radius * 2 * avgUnitsPerPixel);
            locations.add(location); 
    	}
    	
    	VisionUtils.sortLocationsByDistance(camera.getLocation(), locations);
		return this;
	}
	
	public FluentCv drawCircles(
			String baseTag, 
			Color color, 
			String... tag) {
		Color centerColor = new HslColor(color).getComplementary();
		Mat mat = get(baseTag);
		if (mat == null) {
			mat = new Mat();
		}
    	for (int i = 0; i < this.mat.cols(); i++) {
    		double[] circle = this.mat.get(0, i);
    		double x = circle[0];
    		double y = circle[1];
    		double radius = circle[2];
        	Core.circle(
        			mat, 
        			new Point(x, y), 
        			(int) radius, 
        			colorToScalar(color), 
        			2);
        	Core.circle(
        			mat, 
        			new Point(x, y), 
        			1, 
        			colorToScalar(centerColor), 
        			2);
    	}
		return store(mat, tag);
		
	}
	
	public FluentCv drawCircles(String baseTag, String... tag) {
		return drawCircles(baseTag, Color.red, tag);
	}
	
	public FluentCv recall(String tag) {
		mat = get(tag);
		return this;
	}
	
	public FluentCv write(File file) throws Exception {
		ImageIO.write(toBufferedImage(), "PNG", file);
		return this;
	}
	
	public FluentCv read(File file, String... tag) throws Exception {
		 return toMat(ImageIO.read(file), tag);
	}
	
	public BufferedImage toBufferedImage() {
        Integer type = null;
        if (mat.type() == CvType.CV_8UC1) {
            type = BufferedImage.TYPE_BYTE_GRAY;
        }
        else if (mat.type() == CvType.CV_8UC3) {
            type = BufferedImage.TYPE_3BYTE_BGR;
        }
        else if (mat.type() == CvType.CV_32F) {
            type = BufferedImage.TYPE_BYTE_GRAY;
            Mat tmp = new Mat();
            mat.convertTo(tmp, CvType.CV_8UC1, 255);
            mat = tmp;
        }
        if (type == null) {
            throw new Error(String.format("Unsupported Mat: type %d, channels %d, depth %d", 
            		mat.type(), 
            		mat.channels(), 
            		mat.depth()));
        }
        BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), type);
        mat.get(0, 0, ((DataBufferByte) image.getRaster().getDataBuffer()).getData());
        return image;
	}

	public FluentCv settleAndCapture(String... tag) {
		checkCamera();
		return toMat(camera.settleAndCapture(), tag);
	}
	
	/**
	 * Set a Camera that can be used for calculations that require a Camera
	 * Location or units per pixel.
	 * @param camera
	 * @return
	 */
	public FluentCv setCamera(Camera camera) {
		this.camera = camera;
		return this;
	}
	
	public FluentCv filterCirclesByDistance(
			Length minDistance,
			Length maxDistance,
			String... tag
			) {
		
		double minDistancePx = VisionUtils.toPixels(minDistance, camera);
		double maxDistancePx = VisionUtils.toPixels(maxDistance, camera);
		return filterCirclesByDistance(
				camera.getWidth() / 2, 
				camera.getHeight() / 2, 
				minDistancePx, 
				maxDistancePx, 
				tag); 
	}
	
	public FluentCv filterCirclesByDistance(
			double originX,
			double originY,
			double minDistance,
			double maxDistance,
			String...tag
			) {
		List<float[]> results = new ArrayList<float[]>();
    	for (int i = 0; i < this.mat.cols(); i++) {
    		float[] circle = new float[3];
    		this.mat.get(0, i, circle);
    		float x = circle[0];
    		float y = circle[1];
    		float radius = circle[2];
    		double distance = Math.sqrt(Math.pow(x - originX, 2) + Math.pow(y - originY, 2));
    		if (distance >= minDistance && distance <= maxDistance) {
    			results.add(new float[] { x, y, radius });
    		}
    	}
    	// It really seems like there must be a better way to do this, but after hours
    	// and hours of trying I can't find one. How the hell do you append an element
    	// of 3 channels to a Mat?!
		Mat r = new Mat(1, results.size(), CvType.CV_32FC3);
		for (int i = 0; i < results.size(); i++) {
			r.put(0, i, results.get(i));
		}
		return store(r, tag);
	}
	
	public FluentCv filterCirclesToLine(Length maxDistance, String... tag) {
		return filterCirclesToLine(VisionUtils.toPixels(maxDistance, camera), tag);
	}
	
	/**
	 * Filter circles as returned from e.g. houghCircles to only those that are within
	 * maxDistance of the best fitting line.
	 * @param tag
	 * @return
	 */
	public FluentCv filterCirclesToLine(double maxDistance, String... tag) {
		// fitLine doesn't like when you give it a zero length array, so just
		// bail if there are not enough points to make a line.
		if (this.mat.cols() < 2) {
			return store(this.mat, tag);
		}
		
    	List<Point> points = new ArrayList<Point>();
    	// collect the circles into a list of points
    	for (int i = 0; i < this.mat.cols(); i++) {
    		float[] circle = new float[3];
    		this.mat.get(0, i, circle);
    		float x = circle[0];
    		float y = circle[1];
    		points.add(new Point(x, y));
    	}
    	
		Point[] line = Ransac.ransac(points, 100, maxDistance);
    	Point a = line[0];
    	Point b = line[1];
		
    	// filter the points by distance from the resulting line
		List<float[]> results = new ArrayList<float[]>();
		for (int i = 0; i < this.mat.cols(); i++) {
    		float[] circle = new float[3];
    		this.mat.get(0, i, circle);
    		Point p = new Point(circle[0], circle[1]);
    		if (pointToLineDistance(a, b, p) <= maxDistance) {
    			results.add(circle);
    		}
    	}
		
    	// It really seems like there must be a better way to do this, but after hours
    	// and hours of trying I can't find one. How the hell do you append an element
    	// of 3 channels to a Mat?!
		Mat r = new Mat(1, results.size(), CvType.CV_32FC3);
		for (int i = 0; i < results.size(); i++) {
			r.put(0, i, results.get(i));
		}
		return store(r, tag);
	}
	
	public Mat mat() {
		return mat;
	}
	
	/**
	 * Calculate the absolute difference between the previously
	 * stored Mat called source1 and the current Mat.
	 * @param source1
	 * @param tag
	 */
	public FluentCv absDiff(String source1, String... tag) {
		Core.absdiff(get(source1), mat, mat);
		return store(mat, tag);
	}
	
	public FluentCv canny(double threshold1, double threshold2, String... tag) {
		Imgproc.Canny(mat, mat, threshold1, threshold2);
		return store(mat, tag);
	}
	
	public FluentCv findContours(List<MatOfPoint> contours, String... tag) {
		Mat hierarchy = new Mat();
		Imgproc.findContours(mat, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE);
		return store(mat, tag);
	}
	
	public FluentCv drawContours(List<MatOfPoint> contours, Color color, int thickness, String... tag) {
		if (color == null) {
			for (int i = 0; i < contours.size(); i++) {
				Imgproc.drawContours(mat, contours, i, indexedColor(i), thickness);
			}
		}
		else {
			Imgproc.drawContours(mat, contours, -1, colorToScalar(color), thickness);
		}
		return store(mat, tag);
	}

	/**
	 * Draw the minAreaRect of each contour. 
	 * @param contours
	 * @param color If null, use a new color for each rect.
	 * @param tag
	 * @return
	 */
	public FluentCv drawContourRects(List<MatOfPoint> contours, Color color, int thickness, String... tag) {
		Scalar color_ = null;
		if (color != null) {
			color_ = colorToScalar(color);
		}
		for (int i = 0; i < contours.size(); i++) {
			MatOfPoint2f contour_ = new MatOfPoint2f();
		    contours.get(i).convertTo(contour_, CvType.CV_32FC2);
			RotatedRect rect = Imgproc.minAreaRect(contour_);
			// From: http://stackoverflow.com/questions/23327502/opencv-how-to-draw-minarearect-in-java
			Point points[] = new Point[4];
		    rect.points(points);
		    if (color == null) {
		    	color_ = indexedColor(i);
		    }
		    for(int j = 0; j < 4; ++j) {
		        Core.line(mat, points[j], points[(j + 1) % 4], color_, thickness);
		    }		
		}
		return store(mat, tag);
	}
	
	private void checkCamera() {
		if (camera == null) {
			throw new Error("Call setCamera(Camera) before calling methods that rely on units per pixel.");
		}
	}
	
	private FluentCv store(Mat mat, String... tag) {
		this.mat = mat;
		if (tag != null && tag.length > 0) {
			// Clone so that future writes to the pipeline Mat
			// don't overwrite our stored one.
			stored.put(tag[0], this.mat.clone());
		}
		return this;
	}
	
	private Mat get(String tag) {
		Mat mat = stored.get(tag);
		if (mat == null) {
			return null;
		}
		// Clone so that future writes to the pipeline Mat
		// don't overwrite our stored one.
		return mat.clone();
	}
	
	private static Scalar colorToScalar(Color color) {
		return new Scalar(
				color.getBlue(), 
				color.getGreen(), 
				color.getRed(), 
				255);
	}
	
	/**
	 * Return a Scalar representing a color from an imaginary list of colors starting at index 0
	 * and extending on to Integer.MAX_VALUE. Can be used to pick a different color for each object
	 * in a list. Colors are not guaranteed to be unique successive colors will be significantly different.
	 * @param i
	 * @return
	 */
	private static Scalar indexedColor(int i) {
		float h = (i * i) % 360;
		float s = Math.max((i * i) % 100, 50);
		float l = Math.max((i * i) % 100, 50);
		Color color = new HslColor(h, s, l).getRGB();
		return colorToScalar(color);
	}
	
    private static BufferedImage convertBufferedImage(BufferedImage src, int type) {
        if (src.getType() == type) {
            return src;
        }
        BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(),
                type);
        Graphics2D g2d = img.createGraphics();
        g2d.drawImage(src, 0, 0, null);
        g2d.dispose();
        return img;
    }
    
	// From http://www.ahristov.com/tutorial/geometry-games/point-line-distance.html
    public static double pointToLineDistance(Point A, Point B, Point P) {
		double normalLength = Math.sqrt((B.x - A.x) * (B.x - A.x) + (B.y - A.y) * (B.y - A.y));
		return Math.abs((P.x - A.x) * (B.y - A.y) - (P.y - A.y) * (B.x - A.x)) / normalLength;
	}
	
	/**
	 * Draw the infinite line defined by the two points to the extents
	 * of the image instead of just between the two points.
	 * From: http://stackoverflow.com/questions/13160722/how-to-draw-line-not-line-segment-opencv-2-4-2
	 * @param img
	 * @param p1
	 * @param p2
	 * @param color
	 */
	private static void infiniteLine(Mat img, Point p1, Point p2, Color color) {
		Point p = new Point(), q = new Point();
		// Check if the line is a vertical line because vertical lines don't
		// have slope
		if (p1.x != p2.x) {
			p.x = 0;
			q.x = img.cols();
			// Slope equation (y1 - y2) / (x1 - x2)
			float m = (float) ((p1.y - p2.y) / (p1.x - p2.x));
			// Line equation: y = mx + b
			float b = (float) (p1.y - (m * p1.x));
			p.y = m * p.x + b;
			q.y = m * q.x + b;
		} else {
			p.x = q.x = p2.x;
			p.y = 0;
			q.y = img.rows();
		}
		Core.line(img, p, q, colorToScalar(color));
	}

    public static void main(String[] args) throws Exception {
    	List<Point> points = new ArrayList<>();
    	List<MatOfPoint> contours = new ArrayList<>();
		FluentCv cv = new FluentCv()
			.read(new File("/Users/jason/Desktop/up.png"), "original")
			.toGray()
			.threshold(60, false)
			.gaussianBlur(3)
			.canny(100, 200)
			.findContours(contours)
			.recall("original")
			.drawContours(contours, null, 1)
			.drawContourRects(contours, null, 2)
			.write(new File("/Users/jason/Desktop/up_out.png"));
    }
}
