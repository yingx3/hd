clc
clear
T=86400;
file_path = 'C:\Users\87856\Desktop\TRIGRSshuicheng\TRIGRSshuicheng\rainfall_tif\'; %raninfall_storage
dem = GRIDobj('DEM.tif');
slope= GRIDobj('slope.tif');
flowdirection= GRIDobj('flow_direction.tif');
zmax = GRIDobj('soil_depth.tif');
depthwt = GRIDobj('depthwt.tif');
Ys = GRIDobj('weight.tif');
Yw = GRIDobj('water_weight.tif');
c = GRIDobj('cohesion.tif');
f = GRIDobj('friction.tif');
Ks = GRIDobj('ks.tif');
Izlt = GRIDobj('IzlT.tif');
D0 = GRIDobj('D0.tif');

[wet1_Phead,wet1_ZMAX,wet1_Fs]=trigrs_wet1(T,file_path,dem,slope,flowdirection,zmax,depthwt,Ys,Yw,c,f,Ks,Izlt,D0);%Model1
[wet2_Phead,wet2_ZMAX,wet2_Fs]=trigrs_wet2(T,file_path,dem,slope,flowdirection,zmax,depthwt,Ys,Yw,c,f,Ks,Izlt,D0);%model2

